package com.wgh.rag.scheduler.reconcile;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 最近一次对账报告的内存登记处。
 *
 * <p>对账作业运行在任意实例上；管理端只读本实例内存，用于快速查看最近一轮结果。
 * 报告的持久化/历史归属后续事件表，不在本轮范围。
 */
@Component
public class ReconcileReportRegistry {

    private final AtomicReference<ReconcileReport> last = new AtomicReference<>(ReconcileReport.notRun());

    public void record(ReconcileReport report) {
        if (report != null) {
            last.set(report);
        }
    }

    public Optional<ReconcileReport> last() {
        ReconcileReport report = last.get();
        return report == null || report.finishedAt() == null ? Optional.empty() : Optional.of(report);
    }
}
