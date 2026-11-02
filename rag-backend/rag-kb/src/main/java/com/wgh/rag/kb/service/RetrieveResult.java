package com.wgh.rag.kb.service;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;

import java.util.List;

/**
 * 检索结果 + 增强指标
 *
 * @param matches         最终 topK 结果
 * @param rerankApplied   是否实际执行了 Rerank 重排序（失败降级为 false）
 * @param rerankLatencyMs Rerank 耗时（未执行/失败时为 0 或实际耗时）
 */
public record RetrieveResult(List<EmbeddingMatch<TextSegment>> matches,
                             boolean rerankApplied,
                             long rerankLatencyMs) {

    public static RetrieveResult empty() {
        return new RetrieveResult(List.of(), false, 0);
    }
}
