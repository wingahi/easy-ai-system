package com.wgh.rag.tool.fallback;

import dev.langchain4j.data.message.ToolExecutionResultMessage;

/**
 * 工具调用兜底链的执行结果。
 *
 * @param message     返回给模型/前端的结果消息（文本格式与改造前一致）
 * @param success     是否成功（替代原先的 {@code startsWith("Error:")} 判断）
 * @param failureKind 失败分类，成功时为 {@code null}
 * @param retryable   该失败类型是否可重试
 * @param attempts    实际执行次数（含首次）
 * @param latencyMs   总耗时（含重试与退避）
 * @param circuitOpen 本次是否因熔断被短路
 * @param budgetExhausted 本次是否因任务预算耗尽被短路
 */
public record ToolInvocationOutcome(ToolExecutionResultMessage message,
                                    boolean success,
                                    ToolFailureKind failureKind,
                                    boolean retryable,
                                    int attempts,
                                    long latencyMs,
                                    boolean circuitOpen,
                                    boolean budgetExhausted) {

    public static ToolInvocationOutcome success(ToolExecutionResultMessage message, int attempts, long latencyMs) {
        return new ToolInvocationOutcome(message, true, null, false, attempts, latencyMs, false, false);
    }

    public static ToolInvocationOutcome failure(ToolExecutionResultMessage message, ToolFailureKind kind,
                                                int attempts, long latencyMs) {
        return new ToolInvocationOutcome(message, false, kind,
                kind != null && kind.retryable(), attempts, latencyMs, false, false);
    }
}
