package com.wgh.rag.kb.port;

import com.wgh.rag.kb.port.vector.VectorScoreType;

import java.util.List;
import java.util.Map;

/**
 * 关键词召回命中。
 *
 * @param highlights 高亮片段；后端不支持时为空列表
 */
public record KeywordHit(String id, double score, VectorScoreType scoreType,
                         String text, Map<String, Object> metadata, List<String> highlights) {

    public KeywordHit {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        highlights = highlights == null ? List.of() : List.copyOf(highlights);
    }
}
