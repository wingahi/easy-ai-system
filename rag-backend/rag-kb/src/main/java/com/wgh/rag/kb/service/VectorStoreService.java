package com.wgh.rag.kb.service;

import com.wgh.rag.config.property.PgVectorProperties;
import com.wgh.rag.llm.factory.ModelCacheManager;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.List;

/**
 * 向量存储服务 - 封装 PostgreSQL PGVector EmbeddingStore
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VectorStoreService {

    private final DataSource dataSource;
    private final PgVectorProperties pgProps;
    private final ModelCacheManager modelCacheManager;

    public PgVectorEmbeddingStore createEmbeddingStore(String tableName, int dimension) {
        log.info("▶ 创建 PgVectorEmbeddingStore: table={}, dimension={}, createTable={}, useIndex={}",
                tableName, dimension, pgProps.getCreateTable(), pgProps.getUseIndex());

        return PgVectorEmbeddingStore.datasourceBuilder()
                .datasource(dataSource)
                .table(tableName)
                .dimension(dimension)
                .createTable(pgProps.getCreateTable())
                .skipCreateVectorExtension(pgProps.getSkipCreateVectorExtension())
                .useIndex(pgProps.getUseIndex())
                .indexListSize(Boolean.TRUE.equals(pgProps.getUseIndex()) ? pgProps.getIndexListSize() : null)
                // 不传 metadataStorageConfig — 用默认 COMBINED_JSON（无需 metadata 列定义）
                .build();
    }

    public void ensureCollection(String tableName, Integer dimension) {
        log.debug("ensureCollection({}, {}) — PgVectorEmbeddingStore.createTable=true 时会自动建表",
                tableName, dimension);
    }

    public void dropCollection(String tableName) {
        try (var conn = dataSource.getConnection();
             var stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS " + tableName);
            log.info("✓ 向量表 {} 已删除", tableName);
        } catch (Exception e) {
            log.warn("删除向量表 {} 失败: {}", tableName, e.getMessage());
        }
    }

    /**
     * 按 fileId 删除向量（reSplit / 删除文件前必须清理，否则旧向量残留导致召回错乱）
     */
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
     * 真实向量化入库：EmbeddingModel embedAll() + PgVectorEmbeddingStore.addAll()
     */
    public void insertVectors(String tableName,
                              List<TextSegment> segments,
                              Long embeddingModelId,
                              Integer dimension) {
        log.info("▶ 向量化入库: table={}, segments={}, embeddingModelId={}, dimension={}",
                tableName, segments.size(), embeddingModelId, dimension);

        if (segments == null || segments.isEmpty()) {
            log.warn("segments 为空，跳过向量化");
            return;
        }

        EmbeddingModel embeddingModel = modelCacheManager.getEmbeddingModel(embeddingModelId);
        if (embeddingModel == null) {
            throw new RuntimeException("找不到 EmbeddingModel id=" + embeddingModelId);
        }

        List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
        log.info("✓ 生成 {} 个向量", embeddings.size());

        PgVectorEmbeddingStore store = createEmbeddingStore(tableName, dimension);
        store.addAll(embeddings, segments);
        log.info("✓ 已写入 pgvector 表 {}", tableName);
    }
}
