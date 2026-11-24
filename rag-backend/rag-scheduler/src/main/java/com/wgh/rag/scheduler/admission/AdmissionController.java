package com.wgh.rag.scheduler.admission;

import com.wgh.rag.execution.error.ExecutionErrorCodes;
import com.wgh.rag.execution.model.ExecutionLane;
import com.wgh.rag.execution.model.ExecutionRequest;
import com.wgh.rag.scheduler.config.SchedulerProperties;
import com.wgh.rag.scheduler.job.JobStore;
import com.wgh.rag.scheduler.lane.LaneExecutors;
import com.wgh.rag.scheduler.metrics.SchedulerMetrics;
import com.wgh.rag.scheduler.policy.EffectiveSchedulePolicy;
import com.wgh.rag.scheduler.policy.PolicyCache;
import com.wgh.rag.scheduler.policy.RejectPolicy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 准入控制。
 *
 * <p>准入只回答「现在能不能再接一个」，不检查配额（配额由调用方在提交前完成）。
 * 先做车道级全局上限，再按 {@code wgh_tenant_schedule_policy}（本地缓存）做租户级并发/排队约束：
 * <ul>
 *   <li>交互式：全局在途 + 队列已满，或租户并发满且拒绝策略为 {@code REJECT} → 拒绝；</li>
 *   <li>后台：全局 PENDING 达上限、租户并发满且 {@code REJECT}、租户排队达 {@code max_queue} → 拒绝；</li>
 *   <li>维护：只受车道全局上限约束，租户策略不参与（清理类作业跨租户共享）。</li>
 * </ul>
 *
 * <p>拒绝策略是「显式拒绝」：不静默丢弃、不回退到提交线程、不无限排队。
 */
@Component
@ConditionalOnProperty(prefix = "wgh.scheduler", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AdmissionController {

    private final SchedulerProperties properties;

    private final LaneExecutors laneExecutors;

    private final JobStore jobStore;

    private final PolicyCache policyCache;

    private final ObjectProvider<SchedulerMetrics> metrics;

    /** 兼容仅需全局准入语义的调用方/测试。 */
    public AdmissionController(SchedulerProperties properties, LaneExecutors laneExecutors, JobStore jobStore) {
        this(properties, laneExecutors, jobStore, null, null);
    }

    @Autowired
    public AdmissionController(SchedulerProperties properties,
                               LaneExecutors laneExecutors,
                               JobStore jobStore,
                               ObjectProvider<PolicyCache> policyCache,
                               ObjectProvider<SchedulerMetrics> metrics) {
        this.properties = properties;
        this.laneExecutors = laneExecutors;
        this.jobStore = jobStore;
        this.policyCache = policyCache == null ? null : policyCache.getIfAvailable();
        this.metrics = metrics;
    }

    /** 计算准入结论。 */
    public AdmissionDecision decide(ExecutionRequest request) {
        ExecutionLane lane = request.lane();
        if (lane == ExecutionLane.INTERACTIVE) {
            return decideInteractive(request);
        }
        long pending = jobStore.countPending(lane);
        if (pending >= laneExecutors.queueCapacity(lane)) {
            return reject(lane, ExecutionErrorCodes.SCHEDULER_REJECTED,
                    "车道 " + lane + " 排队已满（" + pending + "/" + laneExecutors.queueCapacity(lane) + "）");
        }
        if (lane == ExecutionLane.BACKGROUND) {
            return decideBackground(request);
        }
        return AdmissionDecision.permit();
    }

    private AdmissionDecision decideInteractive(ExecutionRequest request) {
        ExecutionLane lane = ExecutionLane.INTERACTIVE;
        if (laneExecutors.inFlight(lane) >= laneExecutors.maxConcurrent(lane)
                && laneExecutors.queued(lane) >= laneExecutors.queueCapacity(lane)) {
            return reject(lane, ExecutionErrorCodes.SCHEDULER_REJECTED,
                    "交互式车道已满（并发 " + laneExecutors.maxConcurrent(lane)
                            + "，队列 " + laneExecutors.queueCapacity(lane) + "）");
        }
        EffectiveSchedulePolicy policy = resolve(request);
        if (policy.rejectPolicy() == RejectPolicy.REJECT
                && jobStore.countTenantActive(policy.tenantId(), lane) >= policy.maxConcurrent()) {
            return reject(lane, ExecutionErrorCodes.TENANT_CONCURRENCY_EXCEEDED,
                    "租户 " + policy.tenantId() + " 交互式并发已达上限（" + policy.maxConcurrent() + "）");
        }
        return AdmissionDecision.permit();
    }

    private AdmissionDecision decideBackground(ExecutionRequest request) {
        ExecutionLane lane = ExecutionLane.BACKGROUND;
        EffectiveSchedulePolicy policy = resolve(request);
        long running = jobStore.countTenantActive(policy.tenantId(), lane);
        if (running >= policy.maxConcurrent() && policy.rejectPolicy() == RejectPolicy.REJECT) {
            return reject(lane, ExecutionErrorCodes.TENANT_CONCURRENCY_EXCEEDED,
                    "租户 " + policy.tenantId() + " 后台并发已达上限（" + policy.maxConcurrent() + "）");
        }
        long queued = jobStore.countPendingByTenant(policy.tenantId(), lane);
        if (queued >= policy.maxQueue()) {
            return reject(lane, ExecutionErrorCodes.TENANT_QUEUE_FULL,
                    "租户 " + policy.tenantId() + " 后台排队已达上限（" + policy.maxQueue() + "）");
        }
        return AdmissionDecision.permit();
    }

    private EffectiveSchedulePolicy resolve(ExecutionRequest request) {
        if (policyCache != null) {
            return policyCache.resolve(request.tenantId(), request.lane());
        }
        return EffectiveSchedulePolicy.defaults(request.tenantId(), request.lane(), properties);
    }

    private AdmissionDecision reject(ExecutionLane lane, String code, String message) {
        SchedulerMetrics schedulerMetrics = metrics == null ? null : metrics.getIfAvailable();
        if (schedulerMetrics != null) {
            schedulerMetrics.recordRejected(lane.name(), code);
        }
        return AdmissionDecision.reject(code, message);
    }
}
