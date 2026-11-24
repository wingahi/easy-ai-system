package com.wgh.rag.kb.port;

import com.wgh.rag.kb.port.vector.CollectionInfo;
import com.wgh.rag.kb.port.vector.ScanPage;
import com.wgh.rag.kb.port.vector.VectorFilter;
import com.wgh.rag.kb.port.vector.VectorMatch;
import com.wgh.rag.kb.port.vector.VectorRecord;
import dev.langchain4j.data.segment.TextSegment;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * 向量存储端口（后端无关契约）。
 *
 * <p>业务侧只依赖本接口，不感知具体实现（pgvector / 独立向量库）。契约只描述语义：
 * 过滤条件由实现翻译为各自方言，分数含义由 {@code VectorScoreType} 显式声明。
 * 具体实现由 {@code VectorStoreRouter} 按配置选择。</p>
 */
public interface VectorStorePort {

    /** 实现的后端名（用于指标标签等观测场景）；未声明时记为 unknown。 */
    default String backendName() {
        return "unknown";
    }

    /**
     * 确保集合存在（幂等）。
     */
    void ensureCollection(String collection, Integer dimension);

    /**
     * 删除整个集合（幂等）。
     */
    void dropCollection(String collection);

    /**
     * 按文件 ID 删除该集合内属于该文件的向量（删除/重切文件前清理）。
     */
    void deleteByFileId(String collection, Long fileId);

    /**
     * 按向量记录 ID 批量删除。
     */
    void deleteByEmbeddingIds(String collection, Collection<String> embeddingIds);

    /**
     * 向量化并写入指定集合。
     *
     * @return 与入参 segments 顺序一致的向量记录 ID（用于回填事实层 embedding_id）
     */
    List<String> insertVectors(String collection, List<TextSegment> segments,
                               Long embeddingModelId, Integer dimension);

    /**
     * 读取集合中已写入的 chunk 内容哈希（metadata.contentHash），用于入库前去重。
     * <p>集合不存在或查询失败时返回空集合（降级为不去重），由实现记录 WARN。
     */
    Set<String> existingContentHashes(String collection);

    /**
     * 统计集合中的向量条数（重建收尾按新表实际行数回填 chunk_count）。
     * <p>集合不存在或查询失败时返回 0，由实现记录 WARN。
     */
    int countVectors(String collection);

    /**
     * 向量相似度检索。
     *
     * @param queryVector 查询向量
     * @param minScore    最低相似度；实现按自身分数语义解释
     * @param filter      元数据/租户过滤；为 null 表示不过滤
     */
    List<VectorMatch> search(String collection, float[] queryVector, int topK,
                             double minScore, VectorFilter filter);

    /**
     * 按记录 ID 幂等写入（已存在则覆盖），用于事实层回填/补偿。
     *
     * @return 与入参 records 顺序一致的记录 ID
     */
    List<String> upsert(String collection, List<VectorRecord> records, int dimension);

    /**
     * 按记录 ID 游标分页扫描集合（供回填/对账使用）。
     *
     * @param cursor    上一页返回的 nextCursor；为空表示从头开始
     * @param batchSize 每页条数
     */
    ScanPage scan(String collection, String cursor, int batchSize);

    /**
     * 集合级元数据（后端类型、维度、行数）。
     */
    CollectionInfo describe(String collection);
}
