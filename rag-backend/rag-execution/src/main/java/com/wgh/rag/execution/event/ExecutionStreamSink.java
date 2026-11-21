package com.wgh.rag.execution.event;

import java.io.IOException;

/**
 * 传输无关的流式事件出口（执行体 → 接入层）。
 *
 * <p>与 {@link ExecutionEventSink} 同属执行事件模型，区别在于本接口面向「一次执行会话的实时输出」：
 * 事件名直接作为 {@code type}，负载可为任意可序列化对象，并提供完成/失败终态。{@link ExecutionEventSink}
 * 面向带 {@code seq} 的作业事件（重放/审计）；本接口面向交互式执行与会话内执行的低延迟输出。</p>
 *
 * <p>可支撑两种部署形态：
 * <ul>
 *   <li>交互式：接入层实现为 SSE/WebSocket 写出；</li>
 *   <li>后台作业：调度层实现为空实现或事件表写入，执行体代码无需感知差异。</li>
 * </ul>
 *
 * <p>{@link #emit(String, Object)} 声明 {@link IOException}，用于表达下游已断开；执行体据此取消执行。
 * 无下游的后台实现不应抛出该异常。实现需容忍多线程调用。
 */
public interface ExecutionStreamSink {

    /**
     * 发送一个事件。
     *
     * @param type    事件名（通用类型见 {@link ExecutionEventTypes}，业务事件由执行体自定义）
     * @param payload 事件负载，可为任意可序列化对象
     * @throws IOException 下游已断开或写出失败
     */
    void emit(String type, Object payload) throws IOException;

    /**
     * 正常结束输出。默认无操作。
     */
    default void complete() {
    }

    /**
     * 异常结束输出。默认无操作。
     *
     * @param error 失败原因
     */
    default void fail(Throwable error) {
    }
}
