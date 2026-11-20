package com.wgh.rag.execution.error;

/**
 * 执行被取消时抛出。
 *
 * <p>执行体应在长循环、模型调用前后等可中断点检查
 * {@link com.wgh.rag.execution.job.ExecutionCancellationToken}，退出时抛出本异常，
 * 由调度层落 {@code CANCELLED} 终态。
 */
public class ExecutionCancelledException extends ExecutionException {

    public ExecutionCancelledException(String executionId, String reason) {
        super(ExecutionErrorCodes.CANCELLED,
                reason == null || reason.isBlank() ? "执行已取消" : reason,
                executionId);
    }
}
