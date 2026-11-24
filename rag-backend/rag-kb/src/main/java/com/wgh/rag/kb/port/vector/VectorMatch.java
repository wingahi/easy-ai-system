package com.wgh.rag.kb.port.vector;

import java.util.Map;

/**
 * 一条检索命中。分数含义由 {@link VectorScoreType} 显式声明，编排层据此决定能否与阈值比较。
 */
public record VectorMatch(String id, double score, VectorScoreType scoreType,
                          String text, Map<String, Object> metadata) {

    public VectorMatch {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
