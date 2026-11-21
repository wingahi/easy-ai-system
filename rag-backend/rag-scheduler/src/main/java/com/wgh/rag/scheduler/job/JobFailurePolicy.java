package com.wgh.rag.scheduler.job;

import com.wgh.rag.execution.error.ExecutionErrorCodes;

import java.time.Duration;

/**
 * 失败处置策略：决定「重试」还是「死信」。
 *
 * <p>独立为纯函数，便于单测覆盖状态机分支，也避免重试语义散落在执行器中。
 */
public final class JobFailurePolicy {

    /** 处置结果。 */
    public enum Outcome {
        /** 回到 PENDING，按退避时间重试。 */
        RETRY,
        /** 置 DEAD，不再重试。 */
        DEAD
    }

    private JobFailurePolicy() {
    }

    /**
     * 判定失败处置。
     *
     * @param attempt      当前尝试次数（首次执行为 1）
     * @param maxAttempts  最大尝试次数
     * @param errorCode    失败错误码，可为 {@code null}
     * @return 重试或死信
     */
    public static Outcome decide(int attempt, int maxAttempts, String errorCode) {
        if (ExecutionErrorCodes.INCOMPATIBLE_PAYLOAD.equals(errorCode)
                || ExecutionErrorCodes.JOB_TYPE_UNKNOWN.equals(errorCode)) {
            return Outcome.DEAD;
        }
        return attempt >= Math.max(1, maxAttempts) ? Outcome.DEAD : Outcome.RETRY;
    }

    /**
     * 第 N 次失败后的退避时长（线性递增）。
     *
     * @param attempt 当前尝试次数
     * @param base    退避基数
     * @return 退避时长
     */
    public static Duration backoff(int attempt, Duration base) {
        Duration safeBase = base == null || base.isNegative() || base.isZero() ? Duration.ofSeconds(30) : base;
        return safeBase.multipliedBy(Math.max(1, attempt));
    }
}
