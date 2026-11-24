package com.wgh.rag.chat.service.stream.replay;

/**
 * 感知「客户端连接已断开」的流式出口。
 *
 * <p>客户端刷新、组件卸载、网络断开都属于传输层事件，不等于执行失败。实现应停止向下游推送、
 * 保持执行状态，把后续交给重连续看/续跑或最终持久化；不得据此把执行落成失败或终态。</p>
 *
 * <p>{@code cancelAction} 仅供显式关闭旧语义时使用（例如总开关关闭），
 * 默认实现不得调用。</p>
 */
@FunctionalInterface
public interface ClientDetachAware {

    /**
     * 客户端连接断开。
     *
     * @param cancelAction 兼容旧语义的取消动作；只有在明确需要「断连即停止」时才可调用
     */
    void onClientDetached(Runnable cancelAction);
}
