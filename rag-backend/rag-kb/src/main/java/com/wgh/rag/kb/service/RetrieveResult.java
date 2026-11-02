package com.wgh.rag.kb.service;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;

import java.util.List;
import java.util.Set;

/**
 * 检索结果 + 增强指标
 *
 * @param matches             最终 topK 结果
 * @param rerankApplied       是否实际执行了 Rerank 重排序（失败降级为 false）
 * @param rerankLatencyMs     Rerank 耗时（未执行/失败时为 0 或实际耗时）
 * @param hybridApplied       是否启用了混合检索（关键词路无命中时仍为 true，结果退化为纯向量序）
 * @param keywordHitIds       仅被关键词路召回（向量路未命中）的文档 embeddingId，用于调试接口标注来源
 * @param transformedQueries  查询变换产物（REWRITE→改写句；MULTI_QUERY→变体；HYDE→假设答案；未启用/降级为空）
 * @param transformLatencyMs  查询变换耗时
 * @param scoreType           最终分数的语义：COSINE（余弦相似度）/ RRF（多路融合分数）/ RERANK（重排相关性分数）
 */
public record RetrieveResult(List<EmbeddingMatch<TextSegment>> matches,
                             boolean rerankApplied,
                             long rerankLatencyMs,
                             boolean hybridApplied,
                             Set<String> keywordHitIds,
                             List<String> transformedQueries,
                             long transformLatencyMs,
                             ScoreType scoreType) {

    /**
     * 分数语义：只有 COSINE / RERANK 的分数可直接与 minScore 阈值比较，RRF 融合分数不能
     */
    public enum ScoreType {
        COSINE, RRF, RERANK
    }

    public RetrieveResult(List<EmbeddingMatch<TextSegment>> matches, boolean rerankApplied, long rerankLatencyMs) {
        this(matches, rerankApplied, rerankLatencyMs, false, Set.of(), List.of(), 0,
                rerankApplied ? ScoreType.RERANK : ScoreType.COSINE);
    }

    public RetrieveResult(List<EmbeddingMatch<TextSegment>> matches, boolean rerankApplied,
                          long rerankLatencyMs, boolean hybridApplied, Set<String> keywordHitIds) {
        this(matches, rerankApplied, rerankLatencyMs, hybridApplied, keywordHitIds, List.of(), 0,
                rerankApplied ? ScoreType.RERANK : ScoreType.COSINE);
    }

    public static RetrieveResult empty() {
        return new RetrieveResult(List.of(), false, 0, false, Set.of(), List.of(), 0, ScoreType.COSINE);
    }
}
