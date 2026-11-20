package com.wgh.rag.execution.model;

import java.time.Instant;
import java.util.Objects;

/**
 * 执行租约。
 *
 * <p>仅后台作业（{@link ExecutionLane#BACKGROUND}）与维护作业（{@link ExecutionLane#MAINTENANCE}）使用：
 * 领取作业的实例持有租约，执行期间周期性续租；租约过期后作业可被其它实例重新领取。
 * 交互式执行不落租约。
 *
 * <p>执行体通过 {@link com.wgh.rag.execution.job.JobContext#heartbeat()} 续租，不直接操作本对象。
 *
 * @param executionId 执行标识
 * @param owner       租约持有者（实例标识）
 * @param acquiredAt  获取时间
 * @param expireAt    过期时间
 * @param heartbeatAt 最近一次续租时间
 */
public record ExecutionLease(
        String executionId,
        String owner,
        Instant acquiredAt,
        Instant expireAt,
        Instant heartbeatAt) {

    public ExecutionLease {
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(expireAt, "expireAt");
    }

    /**
     * 判断租约在给定时点是否已过期。
     *
     * @param now 当前时间
     * @return {@code true} 表示已过期，作业可被重新领取
     */
    public boolean isExpired(Instant now) {
        return !now.isBefore(expireAt);
    }
}
