package com.wgh.rag.kb.service;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;

import java.util.List;
import java.util.Set;

/**
 * 检索结果 + 增强指标
 *
 * @param matches         最终 topK 结果
 * @param rerankApplied   是否实际执行了 Rerank 重排序（失败降级为 false）
 * @param rerankLatencyMs Rerank 耗时（未执行/失败时为 0 或实际耗时）
 * @param hybridApplied   是否启用了混合检索（关键词路无命中时仍为 true，结果退化为纯向量序）
 * @param keywordHitIds   仅被关键词路召回（向量路未命中）的文档 embeddingId，用于调试接口标注来源
 */
public record RetrieveResult(List<EmbeddingMatch<TextSegment>> matches,
                             boolean rerankApplied,
                             long rerankLatencyMs,
                             boolean hybridApplied,
                             Set<String> keywordHitIds) {

    public RetrieveResult(List<EmbeddingMatch<TextSegment>> matches, boolean rerankApplied, long rerankLatencyMs) {
        this(matches, rerankApplied, rerankLatencyMs, false, Set.of());
    }

    public static RetrieveResult empty() {
        return new RetrieveResult(List.of(), false, 0, false, Set.of());
    }
}
