package com.wgh.rag.kb.splitter;

/**
 * 切分上下文：调用方提供的可选资源。
 *
 * @param embeddingModelId 知识库绑定的 embedding 模型 ID（SEMANTIC 策略需要，其余策略忽略）
 */
public record SplitContext(Long embeddingModelId) {

    public static SplitContext empty() {
        return new SplitContext(null);
    }
}
