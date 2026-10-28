package com.wgh.rag.kb.splitter;

import java.util.List;

/**
 * 文本切分策略：每种分割方式一个实现，优先级由 {@link #priority()} 声明（章节=1 段落=2 长度=3）
 */
public interface ChunkingStrategy {

    /**
     * 对应的策略枚举
     */
    TextSplitter.Strategy type();

    /**
     * 策略优先级（替代硬编码顺序）：章节=1 段落=2 长度=3，其余按注册顺序排列
     */
    int priority();

    /**
     * 切分文本
     *
     * @param paragraphs   解析后的原始段落
     * @param customConfig 自定义配置（JSON 字符串，可选）
     */
    List<String> split(List<String> paragraphs, String customConfig);
}
