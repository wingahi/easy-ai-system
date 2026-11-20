package com.wgh.rag.execution.event;

/**
 * 执行事件出口。
 *
 * <p>执行体只依赖本接口，不感知传输方式：
 * <ul>
 *   <li>交互式：由接入层实现为 SSE（服务层不得出现 {@code SseEmitter}）；</li>
 *   <li>后台作业：实现为空 sink 或落事件表，供重连/审计读取；</li>
 *   <li>测试：可实现为收集器。</li>
 * </ul>
 *
 * <p>实现必须容忍多线程调用（执行体内可能有并行分支），并保证同一 {@code executionId}
 * 的事件顺序与 {@link ExecutionEvent#seq()} 一致。
 */
@FunctionalInterface
public interface ExecutionEventSink {

    /**
     * 发送事件。
     *
     * @param event 执行事件
     */
    void emit(ExecutionEvent event);

    /**
     * 关闭出口，释放传输资源。默认无操作。
     */
    default void close() {
    }
}
