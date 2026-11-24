package com.wgh.rag.kb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.common.metrics.RagMetrics;
import com.wgh.rag.config.property.PgVectorProperties;
import com.wgh.rag.kb.port.VectorStoreBackend;
import com.wgh.rag.kb.port.vector.CollectionInfo;
import com.wgh.rag.kb.port.vector.ScanPage;
import com.wgh.rag.kb.port.vector.VectorFilter;
import com.wgh.rag.kb.port.vector.VectorMatch;
import com.wgh.rag.kb.port.vector.VectorRange;
import com.wgh.rag.kb.port.vector.VectorRecord;
import com.wgh.rag.kb.port.vector.VectorScoreType;
import com.wgh.rag.llm.factory.ModelCacheManager;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import dev.langchain4j.store.embedding.filter.logical.And;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 向量存储服务 - PostgreSQL PGVector 实现（对外只暴露 {@link com.wgh.rag.kb.port.VectorStorePort} 契约）。
 *
 * <p>PG 方言（表名、JSONB metadata、HNSW、embedding_id uuid）全部收敛在本类；
 * 业务侧不接触 {@link EmbeddingStore}。默认后端，行为与引入端口化前一致。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VectorStoreService implements VectorStoreBackend {

    /** 表名白名单：仅允许字母数字下划线（向量表名为系统生成，杜绝注入） */
    private static final Pattern TABLE_NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_]+$");

    private final DataSource dataSource;
    private final PgVectorProperties pgProps;
    private final ModelCacheManager modelCacheManager;
    private final ObjectMapper objectMapper;

    /** 索引构建耗时指标；字段注入避免改变既有构造器签名（单测直接 new 时不注入）。 */
    @Autowired(required = false)
    private RagMetrics metrics;

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
    public String backendName() {
        return "pgvector";
    }

    /**
     * 获取/创建指定集合的向量存储句柄（PG 实现内部使用，不属于跨后端契约）。
     */
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
        if (!validTable(tableName)) return;
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
        if (!tableExists(tableName)) {
            log.debug("向量表 {} 不存在，跳过 fileId={} 清理", tableName, fileId);
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
        if (!tableExists(tableName)) {
            log.debug("向量表 {} 不存在，跳过向量删除", tableName);
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
        if (!tableExists(tableName)) {
            log.debug("向量表 {} 不存在，跳过去重检查", tableName);
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
        if (!tableExists(tableName)) {
            log.debug("向量表 {} 不存在，按 0 计", tableName);
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
     * 真实向量化入库：EmbeddingModel embedAll() + PgVectorEmbeddingStore.addAll()。
     *
     * @return 与 segments 顺序一致的向量记录 ID
     */
    @Override
    public List<String> insertVectors(String tableName,
                                      List<TextSegment> segments,
                                      Long embeddingModelId,
                                      Integer dimension) {
        if (segments == null || segments.isEmpty()) {
            log.warn("segments 为空，跳过向量化");
            return List.of();
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
        List<String> ids = store.addAll(embeddings, segments);
        log.info("✓ 已写入 pgvector 表 {}", tableName);
        return ids == null ? List.of() : ids;
    }

    /**
     * 幂等写入：先按记录 ID 删除同 ID 旧向量，再带 ID 写入。
     * <p>pgvector 表以 embedding_id 为主键，重复写入同一 ID 必须先删，否则唯一约束冲突。</p>
     */
    @Override
    public List<String> upsert(String collection, List<VectorRecord> records, int dimension) {
        if (collection == null || records == null || records.isEmpty()) return List.of();
        ensureCollection(collection, dimension);
        List<String> ids = new ArrayList<>(records.size());
        List<String> existing = new ArrayList<>();
        for (VectorRecord record : records) {
            if (record == null || record.vector() == null) {
                throw new IllegalArgumentException("pgvector upsert 要求记录携带已计算向量: " + collection);
            }
            if (record.id() != null && !record.id().isBlank()) {
                existing.add(record.id());
            }
        }
        if (!existing.isEmpty()) {
            deleteByEmbeddingIds(collection, existing);
        }
        EmbeddingStore<TextSegment> store = createEmbeddingStore(collection, dimension);
        ensureHnswIndex(collection);
        List<Embedding> embeddings = new ArrayList<>(records.size());
        List<TextSegment> segments = new ArrayList<>(records.size());
        for (VectorRecord record : records) {
            String id = record.id() == null || record.id().isBlank()
                    ? java.util.UUID.randomUUID().toString() : record.id();
            ids.add(id);
            embeddings.add(Embedding.from(record.vector()));
            segments.add(TextSegment.from(record.text(), Metadata.from(record.metadata())));
        }
        store.addAll(ids, embeddings, segments);
        log.debug("pgvector upsert {} 条到 {}", ids.size(), collection);
        return ids;
    }

    /**
     * 按 embedding_id 游标分页扫描（不加载向量，仅原文与元数据）。
     */
    @Override
    public ScanPage scan(String collection, String cursor, int batchSize) {
        if (!validTable(collection) || batchSize <= 0 || !tableExists(collection)) {
            return ScanPage.empty();
        }
        String sql = "SELECT embedding_id, text, metadata FROM " + collection
                + (cursor != null && !cursor.isBlank() ? " WHERE embedding_id > ?::uuid" : "")
                + " ORDER BY embedding_id LIMIT ?";
        List<VectorRecord> records = new ArrayList<>();
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(sql)) {
            int idx = 1;
            if (cursor != null && !cursor.isBlank()) {
                ps.setString(idx++, cursor);
            }
            ps.setInt(idx, batchSize);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    String id = rs.getString("embedding_id");
                    records.add(new VectorRecord(id, rs.getString("text"),
                            parseMetadata(rs.getString("metadata")), null));
                }
            }
        } catch (Exception e) {
            log.warn("扫描向量表 {} 失败: {}", collection, e.getMessage());
            return ScanPage.empty();
        }
        String next = records.size() < batchSize ? null : records.get(records.size() - 1).id();
        return new ScanPage(records, next);
    }

    @Override
    public CollectionInfo describe(String collection) {
        if (collection == null) {
            return new CollectionInfo(null, backendName(), null, 0);
        }
        if (!validTable(collection) || !tableExists(collection)) {
            return new CollectionInfo(collection, backendName(), null, 0);
        }
        Integer dimension = null;
        try (var conn = dataSource.getConnection();
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery(
                     "SELECT vector_dims(embedding) FROM " + collection + " LIMIT 1")) {
            if (rs.next()) {
                dimension = rs.getInt(1);
            }
        } catch (Exception e) {
            log.debug("读取向量表 {} 维度失败: {}", collection, e.getMessage());
        }
        return new CollectionInfo(collection, backendName(), dimension, countVectors(collection));
    }

    /**
     * 向量相似度检索：过滤条件翻译为 pgvector 的 metadata JSONB 谓词。
     */
    @Override
    public List<VectorMatch> search(String collection, float[] queryVector, int topK,
                                    double minScore, VectorFilter filter) {
        if (queryVector == null || topK <= 0 || !validTable(collection)) return List.of();
        Integer dimension = dimensionOf(collection, queryVector.length);
        EmbeddingStore<TextSegment> store = createEmbeddingStore(collection, dimension);
        ensureHnswIndex(collection);

        var builder = EmbeddingSearchRequest.builder()
                .queryEmbedding(Embedding.from(queryVector))
                .maxResults(topK)
                .minScore(Math.max(0.0, minScore));
        Filter translated = translate(filter);
        if (translated != null) {
            builder.filter(translated);
        }
        List<EmbeddingMatch<TextSegment>> matches = store.search(builder.build()).matches();
        List<VectorMatch> result = new ArrayList<>(matches.size());
        for (EmbeddingMatch<TextSegment> match : matches) {
            TextSegment segment = match.embedded();
            result.add(new VectorMatch(match.embeddingId(),
                    match.score() != null ? match.score() : 0.0, VectorScoreType.COSINE,
                    segment != null ? segment.text() : null,
                    segment != null ? segment.metadata().toMap() : Map.of()));
        }
        return result;
    }

    /**
     * 向量检索使用的维度：优先库内实际维度（表已存在），否则用查询向量维度。
     */
    private Integer dimensionOf(String collection, int fallback) {
        try (var conn = dataSource.getConnection();
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery(
                     "SELECT atttypmod FROM pg_attribute WHERE attrelid = to_regclass('" + collection
                             + "') AND attname = 'embedding'")) {
            // pgvector 维度编码在 atttypmod 中；取不到时退回查询向量维度
            if (rs.next() && rs.getInt(1) > 4) {
                return rs.getInt(1) - 4;
            }
        } catch (Exception e) {
            log.debug("读取向量表 {} 维度失败，使用查询维度 {}", collection, fallback);
        }
        return fallback;
    }

    /** 后端无关过滤 → pgvector metadata 谓词；空条件返回 null。 */
    private Filter translate(VectorFilter filter) {
        if (filter == null || filter.isEmpty()) return null;
        List<Filter> conditions = new ArrayList<>();
        if (filter.tenantId() != null) {
            conditions.add(MetadataFilterBuilder.metadataKey("tenantId").isEqualTo(filter.tenantId()));
        }
        for (Map.Entry<String, Object> entry : filter.equals().entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) continue;
            conditions.add(equality(entry.getKey(), entry.getValue()));
        }
        for (Map.Entry<String, VectorRange> entry : filter.ranges().entrySet()) {
            VectorRange range = entry.getValue();
            if (entry.getKey() == null || range == null) continue;
            if (range.min() != null) {
                conditions.add(MetadataFilterBuilder.metadataKey(entry.getKey()).isGreaterThanOrEqualTo(range.min()));
            }
            if (range.max() != null) {
                conditions.add(MetadataFilterBuilder.metadataKey(entry.getKey()).isLessThanOrEqualTo(range.max()));
            }
        }
        if (conditions.isEmpty()) return null;
        Filter combined = conditions.get(0);
        for (int i = 1; i < conditions.size(); i++) {
            combined = new And(combined, conditions.get(i));
        }
        return combined;
    }

    private Filter equality(String key, Object value) {
        if (value instanceof Integer i) return MetadataFilterBuilder.metadataKey(key).isEqualTo(i.intValue());
        if (value instanceof Long l) return MetadataFilterBuilder.metadataKey(key).isEqualTo(l.longValue());
        if (value instanceof Double d) return MetadataFilterBuilder.metadataKey(key).isEqualTo(d.doubleValue());
        if (value instanceof Float f) return MetadataFilterBuilder.metadataKey(key).isEqualTo(f.floatValue());
        return MetadataFilterBuilder.metadataKey(key).isEqualTo(String.valueOf(value));
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
        long start = System.nanoTime();
        try (var conn = dataSource.getConnection();
             var stmt = conn.createStatement()) {
            stmt.execute(sql);
            log.info("✓ HNSW 索引已就绪: table={}, m={}, ef_construction={}", tableName, m, efConstruction);
            if (metrics != null) {
                metrics.recordIndexBuild(backendName(), tableName, System.nanoTime() - start);
            }
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

    private boolean validTable(String tableName) {
        if (tableName == null || !TABLE_NAME_PATTERN.matcher(tableName).matches()) {
            log.warn("非法向量表名，拒绝操作: {}", tableName);
            return false;
        }
        return true;
    }

    private boolean tableExists(String tableName) {
        try (var conn = dataSource.getConnection();
             var check = conn.createStatement();
             var rs = check.executeQuery(
                     "SELECT 1 FROM information_schema.tables WHERE table_schema='public' AND table_name='"
                             + tableName + "'")) {
            return rs.next();
        } catch (Exception e) {
            log.warn("检查向量表 {} 失败: {}", tableName, e.getMessage());
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMetadata(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
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
