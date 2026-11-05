package com.wgh.rag.kb.port;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * 向量存储端口。
 * <p>
 * 业务侧只依赖本接口，不感知具体实现（当前为 PostgreSQL + pgvector）。
 * 未来替换向量后端时新增实现即可，检索/入库/清理业务无需改动。
 */
public interface VectorStorePort {

    /**
     * 获取/创建指定集合的向量存储句柄。
     */
    EmbeddingStore<TextSegment> createEmbeddingStore(String tableName, int dimension);

    /**
     * 确保集合存在。
     */
    void ensureCollection(String tableName, Integer dimension);

    /**
     * 删除整个集合。
     */
    void dropCollection(String tableName);

    /**
     * 按文件 ID 删除向量（重切/删除文件前清理旧向量）。
     */
    void deleteByFileId(String tableName, Long fileId);

    /**
     * 按向量记录 ID 批量删除。
     */
    void deleteByEmbeddingIds(String tableName, Collection<String> embeddingIds);

    /**
     * 向量化并写入指定集合。
     */
    void insertVectors(String tableName, List<TextSegment> segments, Long embeddingModelId, Integer dimension);

    /**
     * 读取集合中已写入的 chunk 内容哈希（metadata.contentHash），用于入库前去重。
     * <p>
     * 表不存在或查询失败时返回空集合（降级为不去重），由实现记录 WARN。
     */
    Set<String> existingContentHashes(String tableName);

    /**
     * 统计集合中的向量条数（重建收尾按新表实际行数回填 chunk_count）。
     * <p>
     * 表不存在或查询失败时返回 0，由实现记录 WARN。
     */
    int countVectors(String tableName);
}
