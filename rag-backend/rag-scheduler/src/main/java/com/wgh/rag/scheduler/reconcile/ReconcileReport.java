package com.wgh.rag.scheduler.reconcile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 最近一次作业/业务对账报告（内存保留，进程重启后清空）。
 *
 * @param finishedAt  对账完成时间；{@code null} 表示本实例启动后尚未执行过
 * @param scanned     本轮检查的作业数
 * @param mismatches  发现的不一致数
 * @param repaired    自动修复数（仅 PENDING 且业务已终态/缺失的作业被取消）
 * @param alertOnly   仅告警数（运行中/已成功与业务状态不一致等，不自动改业务状态）
 * @param byType      不一致类型分布
 * @param findings    最近若干条明细（有上限，避免报告过大）
 */
public record ReconcileReport(
        LocalDateTime finishedAt,
        int scanned,
        int mismatches,
        int repaired,
        int alertOnly,
        Map<String, Integer> byType,
        List<Finding> findings) {

    /** 未执行过对账时的空报告。 */
    public static ReconcileReport notRun() {
        return new ReconcileReport(null, 0, 0, 0, 0, Map.of(), List.of());
    }

    /**
     * 单条不一致明细。
     *
     * @param mismatchType 不一致类型
     * @param action       AUTO_REPAIRED / ALERT_ONLY
     */
    public record Finding(
            String executionId,
            String jobType,
            String jobStatus,
            String businessType,
            String businessId,
            String businessStatus,
            String mismatchType,
            String action,
            String detail) {
    }
}
