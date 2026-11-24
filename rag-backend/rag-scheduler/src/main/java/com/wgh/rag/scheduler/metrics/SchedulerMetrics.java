package com.wgh.rag.scheduler.metrics;

import com.wgh.rag.execution.model.ExecutionLane;
import com.wgh.rag.scheduler.lane.LaneExecutors;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 调度中心多副本相关指标。
 *
 * <p>只暴露有限枚举标签（lane / result / type），不写 instanceId、executionId 等高基数值。
 * 单飞不可用、实例心跳失败、失联回收等「降级与故障」必须同时有指标与日志，不允许静默。
 */
@Component
@ConditionalOnProperty(prefix = "wgh.scheduler", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SchedulerMetrics {

    /** 实例心跳写入失败次数（数据库不可用/实例表未迁移）。 */
    public static final String INSTANCE_HEARTBEAT_FAILURES = "wgh.scheduler.instance.heartbeat.failures";
    /** 探测到的失联实例数（每个实例每次扫描只计一次）。 */
    public static final String INSTANCE_DEAD_DETECTED = "wgh.scheduler.instance.dead.detected";
    /** 失联实例持有的作业被提前回收数。 */
    public static final String INSTANCE_RECLAIMED = "wgh.scheduler.instance.reclaimed";
    /** 租约自然过期回收数（不含失联实例提前回收）。 */
    public static final String LEASE_EXPIRED = "wgh.scheduler.lease.expired";
    /** 单飞锁事件（result=acquired/busy/unavailable/renew_failed/release_failed）。 */
    public static final String SINGLE_FLIGHT_EVENTS = "wgh.scheduler.singleflight.events";
    /** 调度循环数据库操作失败次数（op=claim/reclaim/requeue/expire/dead-instance）。 */
    public static final String DISPATCH_ERRORS = "wgh.scheduler.dispatch.errors";
    /** 对账发现的不一致数（type=business_terminal_job_active/business_active_job_terminal/business_missing）。 */
    public static final String RECONCILE_MISMATCH = "wgh.scheduler.reconcile.mismatch";
    /** 对账自动修复数（type=job_cancelled）。 */
    public static final String RECONCILE_REPAIRED = "wgh.scheduler.reconcile.repaired";
    /** 准入拒绝数（lane + reason）。 */
    public static final String JOB_REJECTED = "wgh.scheduler.rejected";
    /** 作业排队时长（lane + jobType）。 */
    public static final String JOB_WAIT = "wgh.scheduler.wait.seconds";
    /** 作业执行时长（jobType + outcome）。 */
    public static final String JOB_DURATION = "wgh.scheduler.job.duration";
    /** 租户策略加载失败次数。 */
    public static final String POLICY_LOAD_FAILURES = "wgh.scheduler.policy.load.failures";

    private final MeterRegistry registry;

    public SchedulerMetrics(MeterRegistry registry, LaneExecutors laneExecutors) {
        this.registry = registry;
        for (ExecutionLane lane : ExecutionLane.values()) {
            Gauge.builder("wgh.scheduler.lane.inflight", laneExecutors, executors -> executors.inFlight(lane))
                    .tag("lane", lane.name())
                    .register(registry);
            Gauge.builder("wgh.scheduler.lane.queued", laneExecutors, executors -> executors.queued(lane))
                    .tag("lane", lane.name())
                    .register(registry);
        }
    }

    public void recordHeartbeatFailure() {
        counter(INSTANCE_HEARTBEAT_FAILURES).increment();
    }

    public void recordDeadDetected(String instanceId) {
        // instanceId 只进日志，不进标签
        counter(INSTANCE_DEAD_DETECTED).increment();
    }

    public void recordInstanceReclaimed(int reclaimed) {
        if (reclaimed > 0) {
            counter(INSTANCE_RECLAIMED).increment(reclaimed);
        }
    }

    public void recordLeaseExpired(int reclaimed) {
        if (reclaimed > 0) {
            counter(LEASE_EXPIRED).increment(reclaimed);
        }
    }

    public void recordSingleFlight(String result) {
        counter(SINGLE_FLIGHT_EVENTS, "result", result == null ? "unknown" : result).increment();
    }

    public void recordDispatchError(String op) {
        counter(DISPATCH_ERRORS, "op", op == null ? "unknown" : op).increment();
    }

    public void recordReconcileMismatch(String type) {
        counter(RECONCILE_MISMATCH, "type", type == null ? "unknown" : type).increment();
    }

    public void recordReconcileRepaired(String type) {
        counter(RECONCILE_REPAIRED, "type", type == null ? "unknown" : type).increment();
    }

    /** 准入拒绝：租户标识不进标签（高基数），只保留车道与原因。 */
    public void recordRejected(String lane, String reason) {
        counter(JOB_REJECTED,
                "lane", lane == null ? "unknown" : lane,
                "reason", reason == null ? "unknown" : reason).increment();
    }

    /** 作业排队时长：从计划时间到实际开始执行。 */
    public void recordWait(String lane, String jobType, java.time.Duration wait) {
        if (wait == null || wait.isNegative()) {
            return;
        }
        io.micrometer.core.instrument.Timer.builder(JOB_WAIT)
                .tag("lane", lane == null ? "unknown" : lane)
                .tag("jobType", jobType == null ? "unknown" : jobType)
                .register(registry)
                .record(wait);
    }

    /** 作业执行时长与结果。 */
    public void recordJobDuration(String jobType, String outcome, java.time.Duration duration) {
        if (duration == null || duration.isNegative()) {
            return;
        }
        io.micrometer.core.instrument.Timer.builder(JOB_DURATION)
                .tag("jobType", jobType == null ? "unknown" : jobType)
                .tag("outcome", outcome == null ? "unknown" : outcome)
                .register(registry)
                .record(duration);
    }

    public void recordPolicyLoadFailure() {
        counter(POLICY_LOAD_FAILURES).increment();
    }

    private Counter counter(String name, String... tags) {
        return registry.counter(name, tags);
    }
}
