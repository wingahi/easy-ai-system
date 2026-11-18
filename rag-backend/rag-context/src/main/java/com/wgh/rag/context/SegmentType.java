package com.wgh.rag.context;

/**
 * 上下文片段类型与默认优先级。
 *
 * <p>数字越小越先满足预算；该顺序来自设计文档 §6.2：
 * system → 当前问题 → 结构化状态块 → 检索片段 → 近期工具结果 → 历史摘要 → 远期事实 → 早期工具结果。
 */
public enum SegmentType {

    /** 系统提示词（不可裁剪） */
    SYSTEM(10),
    /** 当前用户问题（不可裁剪） */
    CURRENT_QUESTION(20),
    /** Agent 结构化状态块 */
    AGENT_STATE(30),
    /** 检索片段 */
    RETRIEVAL(40),
    /** 近期工具结果 */
    RECENT_TOOL_RESULT(50),
    /** 中期摘要 */
    HISTORY_SUMMARY(60),
    /** 远期事实 */
    FAR_FACT(70),
    /** 更早的工具结果 */
    EARLY_TOOL_RESULT(80);

    private final int priority;

    SegmentType(int priority) {
        this.priority = priority;
    }

    public int priority() {
        return priority;
    }
}
