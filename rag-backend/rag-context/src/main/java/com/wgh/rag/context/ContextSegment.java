package com.wgh.rag.context;

/**
 * 参与统一预算分配的一段上下文。
 *
 * @param index     调用方自定义的稳定标识（如检索片段下标 / 消息下标），用于把裁剪结果映射回业务对象
 * @param type      片段类型，决定默认优先级
 * @param content   片段文本
 * @param trimmable 预算不足时是否允许按句界裁剪；false 表示放不下就整段丢弃
 */
public record ContextSegment(int index, SegmentType type, String content, boolean trimmable) {

    public ContextSegment {
        if (type == null) {
            throw new IllegalArgumentException("segment type must not be null");
        }
        if (content == null) {
            content = "";
        }
    }

    /** 默认可裁剪。 */
    public ContextSegment(int index, SegmentType type, String content) {
        this(index, type, content, true);
    }

    public int priority() {
        return type.priority();
    }
}
