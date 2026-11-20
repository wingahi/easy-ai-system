package com.wgh.rag.execution.error;

/**
 * 租约丢失时抛出。
 *
 * <p>执行体在 {@link com.wgh.rag.execution.job.JobContext#heartbeat()} 续租失败后应停止写入，
 * 避免与接管实例产生双写。
 */
public class LeaseLostException extends ExecutionException {

    public LeaseLostException(String executionId, String message) {
        super(ExecutionErrorCodes.LEASE_LOST, message, executionId);
    }
}
