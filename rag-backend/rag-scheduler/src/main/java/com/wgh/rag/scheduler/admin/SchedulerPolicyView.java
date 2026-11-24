package com.wgh.rag.scheduler.admin;

import java.time.LocalDateTime;

/**
 * 租户车道策略视图（管理端）。
 *
 * @param tenantId           租户标识
 * @param lane               执行车道
 * @param enabled            策略是否启用
 * @param maxConcurrent      运行中并发上限
 * @param maxQueue           等待中排队上限
 * @param weight             公平调度权重
 * @param priorityBoost      作业优先级加成
 * @param rejectPolicy       拒绝方式 REJECT / QUEUE
 * @param queueTimeoutSeconds 排队超时秒数
 * @param dailyJobLimit      每日作业数上限（0=不限）
 * @param ratePerMinute      每分钟提交上限（0=不限）
 * @param timeWindowJson     允许运行时间窗
 * @param remark             备注
 * @param version            策略表版本
 * @param custom             是否来自策略表覆盖（false 表示代码默认值）
 * @param running            当前运行中作业数
 * @param queued             当前排队作业数
 * @param updateTime         最近更新时间
 */
public record SchedulerPolicyView(
        String tenantId,
        String lane,
        boolean enabled,
        int maxConcurrent,
        int maxQueue,
        int weight,
        int priorityBoost,
        String rejectPolicy,
        int queueTimeoutSeconds,
        int dailyJobLimit,
        int ratePerMinute,
        String timeWindowJson,
        String remark,
        int version,
        boolean custom,
        long running,
        long queued,
        LocalDateTime updateTime) {
}
