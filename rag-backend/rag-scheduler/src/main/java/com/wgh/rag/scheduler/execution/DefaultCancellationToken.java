package com.wgh.rag.scheduler.execution;

import com.wgh.rag.execution.job.ExecutionCancellationToken;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 默认取消令牌：只置位，不强制中断线程（协作式取消）。
 *
 * <p>同实例内由调度层直接置位；跨实例通过作业表 {@code cancel_requested} 轮询传播。
 */
public class DefaultCancellationToken implements ExecutionCancellationToken {

    private final AtomicReference<String> reason = new AtomicReference<>();

    /**
     * 请求取消；第一次调用生效，后续调用不覆盖原因。
     *
     * @param reason 取消原因
     * @return {@code true} 表示本次调用真正置位
     */
    public boolean cancel(String reason) {
        String normalized = reason == null || reason.isBlank() ? "执行已取消" : reason;
        return this.reason.compareAndSet(null, normalized);
    }

    @Override
    public boolean isCancelled() {
        return reason.get() != null;
    }

    @Override
    public String reason() {
        return reason.get();
    }
}
