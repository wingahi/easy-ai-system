package com.wgh.rag.tool.fallback;

/**
 * 工具结果结构化信封。
 *
 * <p>原始返回文本格式保持不变（仍为 {@code Error: ...} / {@code HTTP <code> - ...}），
 * 信封只在上层需要「摘要 / 分页 / 结构化状态」时提供统一视图：
 * <ul>
 *   <li>{@code content}：本页内容（默认 4000 字符一页）；</li>
 *   <li>{@code summary}：要点摘要（JSON 对象给出字段清单，数组给出条目数，文本取首段）；</li>
 *   <li>{@code nextCursor}：下一页游标（已消费字符偏移），为 null 表示没有更多；</li>
 *   <li>{@code failureKind}：失败分类，成功时为 null。</li>
 * </ul>
 *
 * @param toolName    工具名
 * @param success     是否成功
 * @param failureKind 失败分类
 * @param content     本页内容
 * @param summary     摘要
 * @param truncated   是否还有未展示内容
 * @param nextCursor  下一页游标
 * @param totalChars  原始文本总字符数
 */
public record ToolResultEnvelope(String toolName,
                                 boolean success,
                                 ToolFailureKind failureKind,
                                 String content,
                                 String summary,
                                 boolean truncated,
                                 String nextCursor,
                                 int totalChars) {

    public boolean hasMore() {
        return nextCursor != null;
    }
}
