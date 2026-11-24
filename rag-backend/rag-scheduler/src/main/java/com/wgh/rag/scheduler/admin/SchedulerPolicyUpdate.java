package com.wgh.rag.scheduler.admin;

/**
 * 租户策略更新项；只覆盖非空字段，{@code version} 用于乐观锁。
 *
 * @param lane               执行车道（必填）
 * @param enabled            是否启用
 * @param maxConcurrent      运行中并发上限
 * @param maxQueue           等待中排队上限
 * @param weight             公平调度权重（1-100）
 * @param priorityBoost      作业优先级加成
 * @param rejectPolicy       REJECT / QUEUE
 * @param queueTimeoutSeconds 排队超时秒数
 * @param dailyJobLimit      每日作业数上限
 * @param ratePerMinute      每分钟提交上限
 * @param timeWindowJson     允许运行时间窗 JSON
 * @param remark             备注
 * @param version            期望版本；新建时为空
 */
public record SchedulerPolicyUpdate(
        String lane,
        Boolean enabled,
        Integer maxConcurrent,
        Integer maxQueue,
        Integer weight,
        Integer priorityBoost,
        String rejectPolicy,
        Integer queueTimeoutSeconds,
        Integer dailyJobLimit,
        Integer ratePerMinute,
        String timeWindowJson,
        String remark,
        Integer version) {
}
