package com.wgh.rag.scheduler.admission;

import com.wgh.rag.execution.error.ExecutionErrorCodes;
import com.wgh.rag.execution.model.ExecutionLane;
import com.wgh.rag.execution.model.ExecutionRequest;
import com.wgh.rag.scheduler.config.SchedulerProperties;
import com.wgh.rag.scheduler.job.JobStore;
import com.wgh.rag.scheduler.lane.LaneExecutors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 准入控制。
 *
 * <p>准入只回答「现在能不能再接一个」，不检查配额（配额由调用方在提交前完成）。
 * 三条车道各自有界：
 * <ul>
 *   <li>交互式：在途数达到最大并发且队列已满 → {@code SCHEDULER_REJECTED}；</li>
 *   <li>后台/维护：作业表 PENDING 数达到车道队列上限 → {@code SCHEDULER_REJECTED}；</li>
 *   <li>后台车道额外限制单租户在跑数 → {@code TENANT_CONCURRENCY_EXCEEDED}。</li>
 * </ul>
 * 拒绝策略是「显式拒绝」：不静默丢弃、不回退到提交线程、不无限排队。
 */
@Component
@ConditionalOnProperty(prefix = "wgh.scheduler", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AdmissionController {

    private final SchedulerProperties properties;

    private final LaneExecutors laneExecutors;

    private final JobStore jobStore;

    public AdmissionController(SchedulerProperties properties, LaneExecutors laneExecutors, JobStore jobStore) {
        this.properties = properties;
        this.laneExecutors = laneExecutors;
        this.jobStore = jobStore;
    }

    /** 计算准入结论。 */
    public AdmissionDecision decide(ExecutionRequest request) {
        ExecutionLane lane = request.lane();
        if (lane == ExecutionLane.INTERACTIVE) {
            return decideInteractive();
        }
        long pending = jobStore.countPending(lane);
        if (pending >= laneExecutors.queueCapacity(lane)) {
            return AdmissionDecision.reject(ExecutionErrorCodes.SCHEDULER_REJECTED,
                    "车道 " + lane + " 排队已满（" + pending + "/" + laneExecutors.queueCapacity(lane) + "）");
        }
        if (lane == ExecutionLane.BACKGROUND) {
            long running = jobStore.countTenantActive(request.tenantId(), lane);
            if (running >= properties.getTenantMaxConcurrent()) {
                return AdmissionDecision.reject(ExecutionErrorCodes.TENANT_CONCURRENCY_EXCEEDED,
                        "租户 " + request.tenantId() + " 后台并发已达上限（" + properties.getTenantMaxConcurrent() + "）");
            }
        }
        return AdmissionDecision.permit();
    }

    private AdmissionDecision decideInteractive() {
        ExecutionLane lane = ExecutionLane.INTERACTIVE;
        if (laneExecutors.inFlight(lane) >= laneExecutors.maxConcurrent(lane)
                && laneExecutors.queued(lane) >= laneExecutors.queueCapacity(lane)) {
            return AdmissionDecision.reject(ExecutionErrorCodes.SCHEDULER_REJECTED,
                    "交互式车道已满（并发 " + laneExecutors.maxConcurrent(lane)
                            + "，队列 " + laneExecutors.queueCapacity(lane) + "）");
        }
        return AdmissionDecision.permit();
    }
}
