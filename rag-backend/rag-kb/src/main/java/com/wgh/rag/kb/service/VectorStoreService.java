package com.wgh.rag.kb.service;

import com.wgh.rag.config.property.PgVectorProperties;
import com.wgh.rag.kb.port.VectorStorePort;
import com.wgh.rag.llm.factory.ModelCacheManager;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 向量存储服务 - PostgreSQL PGVector 实现（对外只暴露 {@link VectorStorePort} 契约）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VectorStoreService implements VectorStorePort {

    private final DataSource dataSource;
    private final PgVectorProperties pgProps;
    private final ModelCacheManager modelCacheManager;

    /**
     * EmbeddingStore 句柄进程内缓存（LRU，上限 storeCacheMaxSize）。
     * <p>
     * key = 表名 + '#' + 维度：知识库与 embedding 模型一一绑定，模型切换会带来维度变化，
     * 因此维度参与 key 即可隔离不同维度表；同维度的不同模型共用句柄是安全的
     * （句柄只持有 DataSource/表名/维度，不绑定具体模型）。
     * <p>
     * 句柄本身无状态（每次检索都直接查库），所以写入/删除不需要失效缓存；
     * 仅"删表/换维度"这类表身份变化需要失效（见 {@link #dropCollection}）。
     */
    private final Map<String, EmbeddingStore<TextSegment>> storeCache =
            Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, EmbeddingStore<TextSegment>> eldest) {
                    int max = pgProps.getStoreCacheMaxSize() != null ? pgProps.getStoreCacheMaxSize() : 64;
                    return size() > max;
                }
            });

    /** 已确保 HNSW 索引存在的表名（进程内去重，避免每次检索重复 DDL） */
    private final Set<String> hnswIndexedTables = ConcurrentHashMap.newKeySet();

    @Override
    public EmbeddingStore<TextSegment> createEmbeddingStore(String tableName, int dimension) {
        String cacheKey = cacheKey(tableName, dimension);
        EmbeddingStore<TextSegment> cached = storeCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        log.debug("构建 PgVectorEmbeddingStore: table={}, dimension={}, createTable={}, useIndex={}",
                tableName, dimension, pgProps.getCreateTable(), pgProps.getUseIndex());

        EmbeddingStore<TextSegment> store = PgVectorEmbeddingStore.datasourceBuilder()
                .datasource(dataSource)
                .table(tableName)
                .dimension(dimension)
                .createTable(pgProps.getCreateTable())
                .skipCreateVectorExtension(pgProps.getSkipCreateVectorExtension())
                .useIndex(pgProps.getUseIndex())
                .indexListSize(Boolean.TRUE.equals(pgProps.getUseIndex()) ? pgProps.getIndexListSize() : null)
                // 不传 metadataStorageConfig — 用默认 COMBINED_JSON（无需 metadata 列定义）
                .build();
        storeCache.put(cacheKey, store);
        return store;
    }

    @Override
    public void ensureCollection(String tableName, Integer dimension) {
        if (tableName == null) return;
        int dim = dimension != null ? dimension : pgProps.getDefaultDimension();
        // 借用 PgVectorEmbeddingStore 的建表能力（CREATE TABLE IF NOT EXISTS）
        createEmbeddingStore(tableName, dim);
        ensureHnswIndex(tableName);
        log.debug("ensureCollection({}, {}) 完成", tableName, dim);
    }

    @Override
    public void dropCollection(String tableName) {
        try (var conn = dataSource.getConnection();
             var stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS " + tableName);
            recallStoredHandle(tableName);
            log.info("✓ 向量表 {} 已删除", tableName);
        } catch (Exception e) {
            log.warn("删除向量表 {} 失败: {}", tableName, e.getMessage());
        }
    }

    /**
     * 按 fileId 删除向量（reSplit / 删除文件前必须清理，否则旧向量残留导致召回错乱）
     */
    @Override
    public void deleteByFileId(String tableName, Long fileId) {
        if (tableName == null || fileId == null) return;
        // 表可能不存在（文件从未成功入库），先检查
        try (var conn = dataSource.getConnection();
             var check = conn.createStatement();
             var rs = check.executeQuery(
                     "SELECT 1 FROM information_schema.tables WHERE table_schema='public' AND table_name='" + tableName + "'")) {
            if (!rs.next()) {
                log.debug("向量表 {} 不存在，跳过 fileId={} 清理", tableName, fileId);
                return;
            }
        } catch (Exception e) {
            log.warn("检查向量表 {} 失败: {}", tableName, e.getMessage());
            return;
        }
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "DELETE FROM " + tableName + " WHERE metadata->>'fileId' = ?")) {
            ps.setString(1, String.valueOf(fileId));
            int n = ps.executeUpdate();
            log.info("✓ 清理 {} 中 fileId={} 的旧向量 {} 条", tableName, fileId, n);
        } catch (Exception e) {
            log.warn("删除向量失败 table={} fileId={}: {}", tableName, fileId, e.getMessage());
        }
    }

    /**
     * 按 embedding_id 批量删除向量（语义缓存淘汰用）
     */
    @Override
    public void deleteByEmbeddingIds(String tableName, java.util.Collection<String> embeddingIds) {
        if (tableName == null || embeddingIds == null || embeddingIds.isEmpty()) return;
        try (var conn = dataSource.getConnection();
             var check = conn.createStatement();
             var rs = check.executeQuery(
                     "SELECT 1 FROM information_schema.tables WHERE table_schema='public' AND table_name='" + tableName + "'")) {
            if (!rs.next()) {
                log.debug("向量表 {} 不存在，跳过向量删除", tableName);
                return;
            }
        } catch (Exception e) {
            log.warn("检查向量表 {} 失败: {}", tableName, e.getMessage());
            return;
        }
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "DELETE FROM " + tableName + " WHERE embedding_id = ?::uuid")) {
            for (String id : embeddingIds) {
                ps.setString(1, id);
                ps.addBatch();
            }
            int[] n = ps.executeBatch();
            log.info("✓ 删除 {} 中 {} 条向量", tableName, n.length);
        } catch (Exception e) {
            log.warn("按 embedding_id 删除向量失败 table={}: {}", tableName, e.getMessage());
        }
    }

    /**
     * 读取集合中已写入的 chunk 内容哈希（metadata.contentHash），入库前去重用。
     * <p>
     * 表不存在返回空集合；查询失败记 WARN 并返回空集合（降级为不去重，不影响入库主流程）。
     */
    @Override
    public Set<String> existingContentHashes(String tableName) {
        if (tableName == null) return Set.of();
        try (var conn = dataSource.getConnection();
             var check = conn.createStatement();
             var rs = check.executeQuery(
                     "SELECT 1 FROM information_schema.tables WHERE table_schema='public' AND table_name='" + tableName + "'")) {
            if (!rs.next()) {
                log.debug("向量表 {} 不存在，跳过去重检查", tableName);
                return Set.of();
            }
        } catch (Exception e) {
            log.warn("检查向量表 {} 失败，跳过去重检查: {}", tableName, e.getMessage());
            return Set.of();
        }

        Set<String> hashes = new HashSet<>();
        try (var conn = dataSource.getConnection();
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery(
                     "SELECT DISTINCT metadata->>'contentHash' AS h FROM " + tableName
                             + " WHERE metadata ? 'contentHash'")) {
            while (rs.next()) {
                String hash = rs.getString(1);
                if (hash != null && !hash.isBlank()) {
                    hashes.add(hash);
                }
            }
        } catch (Exception e) {
            log.warn("读取 contentHash 失败 table={}，本次入库不去重: {}", tableName, e.getMessage());
            return Set.of();
        }
        return hashes;
    }

    @Override
    public int countVectors(String tableName) {
        if (tableName == null) return 0;
        try (var conn = dataSource.getConnection();
             var check = conn.createStatement();
             var rs = check.executeQuery(
                     "SELECT 1 FROM information_schema.tables WHERE table_schema='public' AND table_name='" + tableName + "'")) {
            if (!rs.next()) {
                log.debug("向量表 {} 不存在，按 0 计", tableName);
                return 0;
            }
        } catch (Exception e) {
            log.warn("检查向量表 {} 失败，按 0 计: {}", tableName, e.getMessage());
            return 0;
        }

        try (var conn = dataSource.getConnection();
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT count(*) FROM " + tableName)) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (Exception e) {
            log.warn("统计向量表 {} 行数失败，按 0 计: {}", tableName, e.getMessage());
            return 0;
        }
    }

    /**
     * 真实向量化入库：EmbeddingModel embedAll() + PgVectorEmbeddingStore.addAll()
     */
    @Override
    public void insertVectors(String tableName,
                              List<TextSegment> segments,
                              Long embeddingModelId,
                              Integer dimension) {
        if (segments == null || segments.isEmpty()) {
            log.warn("segments 为空，跳过向量化");
            return;
        }
        log.info("▶ 向量化入库: table={}, segments={}, embeddingModelId={}, dimension={}",
                tableName, segments.size(), embeddingModelId, dimension);

        EmbeddingModel embeddingModel = modelCacheManager.getEmbeddingModel(embeddingModelId);
        if (embeddingModel == null) {
            throw new RuntimeException("找不到 EmbeddingModel id=" + embeddingModelId);
        }

        List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
        log.info("✓ 生成 {} 个向量", embeddings.size());

        EmbeddingStore<TextSegment> store = createEmbeddingStore(tableName, dimension);
        // 空表首次建索引代价极低；历史表若已存在，由 schema.sql 补建或人工 SQL 处理，避免请求线程执行长 DDL
        ensureHnswIndex(tableName);
        store.addAll(embeddings, segments);
        log.info("✓ 已写入 pgvector 表 {}", tableName);
    }

    /**
     * 幂等创建 HNSW 索引（cosine 距离）。
     * <p>
     * pgvector &gt;= 0.5.0 才支持 HNSW；扩展缺失/权限不足时只告警，不影响检索主流程。
     * 进程内按表名去重，同一张表每个进程最多执行一次 DDL。
     */
    void ensureHnswIndex(String tableName) {
        if (tableName == null || !Boolean.TRUE.equals(pgProps.getUseHnswIndex())) return;
        if (!hnswIndexedTables.add(tableName)) return;

        int m = pgProps.getHnswM() != null ? pgProps.getHnswM() : 16;
        int efConstruction = pgProps.getHnswEfConstruction() != null ? pgProps.getHnswEfConstruction() : 64;
        String sql = "CREATE INDEX IF NOT EXISTS " + hnswIndexName(tableName)
                + " ON " + tableName
                + " USING hnsw (embedding vector_cosine_ops)"
                + " WITH (m = " + m + ", ef_construction = " + efConstruction + ")";
        try (var conn = dataSource.getConnection();
             var stmt = conn.createStatement()) {
            stmt.execute(sql);
            log.info("✓ HNSW 索引已就绪: table={}, m={}, ef_construction={}", tableName, m, efConstruction);
        } catch (Exception e) {
            // 允许后续重试（例如扩容后权限恢复）
            hnswIndexedTables.remove(tableName);
            log.warn("创建 HNSW 索引失败（降级为无 ANN 索引检索）: table={}, reason={}", tableName, e.getMessage());
        }
    }

    /** 索引名保持 <=63 字节（PostgreSQL 标识符上限），与 schema.sql 补建脚本保持一致 */
    static String hnswIndexName(String tableName) {
        return tableName + "_hnsw_idx";
    }

    private static String cacheKey(String tableName, int dimension) {
        return tableName + "#" + dimension;
    }

    /** 删表后移除该表的全部维度句柄（表身份变化，旧句柄不应再被复用） */
    private void recallStoredHandle(String tableName) {
        String prefix = tableName + "#";
        synchronized (storeCache) {
            storeCache.keySet().removeIf(k -> k.startsWith(prefix));
        }
        hnswIndexedTables.remove(tableName);
    }
}
