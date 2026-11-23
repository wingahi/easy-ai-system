package com.wgh.rag.execution.run;

/**
 * 后台续跑的生命周期钩子。
 *
 * <p>执行域在节点/步骤边界调用：{@link #heartbeat()} 续租，{@link #throwIfCancelled()}
 * 响应取消或租约丢失。租约丢失时必须抛出
 * {@link com.wgh.rag.execution.error.LeaseLostException}，执行域不得再写业务终态。
 */
public interface RunLifecycle {

    /** 无操作实现：交互式执行或 HITL 恢复使用。 */
    RunLifecycle NOOP = new RunLifecycle() {
        @Override
        public void heartbeat() {
            // 无租约
        }

        @Override
        public void throwIfCancelled() {
            // 交互式取消由各执行域既有逻辑处理
        }
    };

    /** 续租心跳。 */
    void heartbeat();

    /** 取消或租约丢失时抛出；正常时返回。 */
    void throwIfCancelled();
}
