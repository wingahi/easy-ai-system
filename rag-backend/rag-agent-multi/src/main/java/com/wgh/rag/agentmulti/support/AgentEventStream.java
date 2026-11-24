package com.wgh.rag.agentmulti.support;

import java.util.Map;

/**
 * 多 Agent 事件出口（传输无关）。
 *
 * <p>执行体只依赖本接口，不再直接持有 {@code SseEmitter}：
 * <ul>
 *   <li>{@link SseAgentEventStream}：HTTP SSE（请求线程/异步线程内推送）</li>
 *   <li>{@code JobAgentEventStream}：后台作业（写入 wgh_execution_event，前端通过 attach 重放）</li>
 * </ul>
 *
 * <p>实现方允许抛出异常表示「下游已断开」；由 {@link SseEventSender} 统一标记关闭，
 * 执行本身继续推进，不把传输断开误判为执行失败。
 */
public interface AgentEventStream {

    /**
     * 发送命名事件。
     *
     * @param eventName 事件名（MultiAgentEvents）
     * @param payload   事件负载（已包含 timestamp）
     */
    void sendEvent(String eventName, Map<String, Object> payload);

    /**
     * 发送注释（SSE 心跳）。
     *
     * @param comment 注释文本
     */
    void sendComment(String comment);

    /** 结束事件流；后台作业实现可忽略（终态由调度层事件表达）。 */
    default void complete() {
        // 默认无操作
    }
}
