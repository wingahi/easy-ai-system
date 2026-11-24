package com.wgh.rag.scheduler.policy;

import com.wgh.rag.execution.model.ExecutionLane;
import com.wgh.rag.scheduler.config.SchedulerProperties;
import com.wgh.rag.scheduler.entity.TenantSchedulePolicy;

/**
 * 生效的租户车道策略（策略表覆盖值或代码默认值）。
 *
 * @param tenantId           租户标识
 * @param lane               执行车道
 * @param maxConcurrent      运行中并发上限
 * @param maxQueue           等待中排队上限
 * @param weight             公平调度权重（1-100）
 * @param priorityBoost      作业基础优先级加成
 * @param rejectPolicy       拒绝方式
 * @param queueTimeoutSeconds 排队超时秒数
 * @param dailyJobLimit      每日作业数上限（0=不限）
 * @param ratePerMinute      每分钟提交上限（0=不限）
 * @param timeWindowJson     允许运行时间窗（为空表示全天）
 * @param custom             是否来自策略表覆盖（{@code false} 表示默认策略）
 * @param version            策略表版本；默认策略为 0
 */
public record EffectiveSchedulePolicy(
        String tenantId,
        ExecutionLane lane,
        int maxConcurrent,
        int maxQueue,
        int weight,
        int priorityBoost,
        RejectPolicy rejectPolicy,
        int queueTimeoutSeconds,
        int dailyJobLimit,
        int ratePerMinute,
        String timeWindowJson,
        boolean custom,
        int version) {

    /** 策略表行 → 生效策略；非法值按字段回退默认值。 */
    public static EffectiveSchedulePolicy from(TenantSchedulePolicy row, EffectiveSchedulePolicy defaults) {
        return new EffectiveSchedulePolicy(
                row.getTenantId(),
                row.getLane() == null ? defaults.lane() : row.getLane(),
                positive(row.getMaxConcurrent(), defaults.maxConcurrent()),
                nonNegative(row.getMaxQueue(), defaults.maxQueue()),
                clampWeight(row.getWeight(), defaults.weight()),
                nonNegative(row.getPriorityBoost(), defaults.priorityBoost()),
                RejectPolicy.parse(row.getRejectPolicy(), defaults.rejectPolicy()),
                nonNegative(row.getQueueTimeoutSeconds(), defaults.queueTimeoutSeconds()),
                nonNegative(row.getDailyJobLimit(), defaults.dailyJobLimit()),
                nonNegative(row.getRatePerMinute(), defaults.ratePerMinute()),
                row.getTimeWindowJson(),
                true,
                row.getVersion() == null ? 0 : Math.max(0, row.getVersion()));
    }

    /** 代码默认策略（{@code wgh.scheduler.default-policy}）。 */
    public static EffectiveSchedulePolicy defaults(String tenantId, ExecutionLane lane,
                                                   SchedulerProperties properties) {
        SchedulerProperties.LanePolicy source = lane == ExecutionLane.INTERACTIVE
                ? properties.getDefaultPolicy().getInteractive()
                : properties.getDefaultPolicy().getBackground();
        RejectPolicy fallbackReject = lane == ExecutionLane.INTERACTIVE
                ? RejectPolicy.REJECT : RejectPolicy.QUEUE;
        return new EffectiveSchedulePolicy(
                tenantId,
                lane,
                Math.max(1, source.getMaxConcurrent()),
                Math.max(0, source.getMaxQueue()),
                clampWeight(source.getWeight(), 10),
                Math.max(0, source.getPriorityBoost()),
                RejectPolicy.parse(source.getRejectPolicy(), fallbackReject),
                Math.max(0, source.getQueueTimeoutSeconds()),
                0,
                0,
                null,
                false,
                0);
    }

    private static int positive(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    private static int nonNegative(Integer value, int fallback) {
        return value == null || value < 0 ? fallback : value;
    }

    private static int clampWeight(Integer value, int fallback) {
        int weight = value == null ? fallback : value;
        return Math.max(1, Math.min(100, weight));
    }
}
