package com.wgh.rag.kb.splitter;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * CUSTOM：自定义分割（通过 config 传递）；当前实现回退段落分割
 */
@Component
@RequiredArgsConstructor
public class CustomChunkingStrategy implements ChunkingStrategy {

    private final ParagraphChunkingStrategy paragraphStrategy;

    @Override
    public TextSplitter.Strategy type() {
        return TextSplitter.Strategy.CUSTOM;
    }

    @Override
    public int priority() {
        return 6;
    }

    @Override
    public List<String> split(List<String> paragraphs, String customConfig) {
        // 简单实现：如果有自定义正则，按正则分割
        // 复杂场景可通过 customConfig JSON 扩展
        return paragraphStrategy.split(paragraphs, customConfig);
    }
}
