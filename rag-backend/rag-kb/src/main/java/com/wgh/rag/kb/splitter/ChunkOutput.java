package com.wgh.rag.kb.splitter;

import java.util.Map;

/**
 * 带属性的切分产物：text 之外携带需要写入 chunk metadata 的属性
 * （如 STRUCTURED 的 headingPath、SEMANTIC 的 semanticBlockIndex）。
 */
public record ChunkOutput(String text, Map<String, Object> attributes) {

    public static ChunkOutput of(String text) {
        return new ChunkOutput(text, Map.of());
    }
}
