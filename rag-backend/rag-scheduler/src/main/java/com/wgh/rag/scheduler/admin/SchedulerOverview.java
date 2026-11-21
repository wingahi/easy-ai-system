package com.wgh.rag.scheduler.admin;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 管理端调度概览。
 *
 * @param generatedAt                统计生成时间
 * @param total                      作业总数
 * @param statusDistribution         按状态分布（缺失状态以 0 补齐）
 * @param laneDistribution           按车道分布（缺失车道以 0 补齐）
 * @param pendingCount               待执行数（PENDING）
 * @param runningCount               在途数（LEASED + RUNNING）
 * @param failedCount                失败数（FAILED）
 * @param deadCount                  死信数（DEAD）
 * @param timeoutOrLeaseExpiredCount 超时或租约过期数
 */
public record SchedulerOverview(
        LocalDateTime generatedAt,
        long total,
        Map<String, Long> statusDistribution,
        Map<String, Long> laneDistribution,
        long pendingCount,
        long runningCount,
        long failedCount,
        long deadCount,
        long timeoutOrLeaseExpiredCount) {
}
