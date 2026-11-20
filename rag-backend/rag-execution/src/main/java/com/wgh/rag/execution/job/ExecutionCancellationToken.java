package com.wgh.rag.execution.job;

/**
 * 执行取消令牌。
 *
 * <p>由调度层为每次执行创建并注入 {@link JobContext}；执行体在可中断点检查，
 * 命中后应尽快退出并抛出
 * {@link com.wgh.rag.execution.error.ExecutionCancelledException}。
 *
 * <p>取消是协作式的：调度层只负责置位与跨实例传播（Redis/DB），不强制中断线程。
 */
public interface ExecutionCancellationToken {

    /**
     * 是否已请求取消。
     *
     * @return {@code true} 表示已请求取消
     */
    boolean isCancelled();

    /**
     * 取消原因，未取消时为 {@code null}。
     *
     * @return 取消原因
     */
    String reason();

    /**
     * 若已请求取消则抛出 {@link com.wgh.rag.execution.error.ExecutionCancelledException}。
     */
    default void throwIfCancellationRequested() {
        if (isCancelled()) {
            throw new com.wgh.rag.execution.error.ExecutionCancelledException(null, reason());
        }
    }
}
