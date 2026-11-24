package com.wgh.rag.scheduler.job;

import com.wgh.rag.common.log.MdcContext;
import com.wgh.rag.execution.job.RecurringJob;
import com.wgh.rag.execution.model.ExecutionLane;
import com.wgh.rag.execution.model.ExecutionRequest;
import com.wgh.rag.scheduler.config.ChatRunProperties;
import com.wgh.rag.scheduler.config.SchedulerProperties;
import com.wgh.rag.scheduler.entity.ExecutionJob;
import com.wgh.rag.scheduler.entity.SchedulerInstance;
import com.wgh.rag.scheduler.lane.LaneExecutors;
import com.wgh.rag.scheduler.metrics.SchedulerMetrics;
import com.wgh.rag.scheduler.support.SchedulerIdentity;
import com.wgh.rag.scheduler.support.SchedulerInstanceRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 后台作业调度循环。
 *
 * <p>单线程循环做四件事：领取可执行作业、回收过期租约、把可重试失败放回队列、把排队超时作业落终态。
 * 领取受车道在途上限约束（{@link LaneExecutors#tryReserve}），不会把线程池队列撑爆。
 *
 * <p>多实例下领取依靠 {@code FOR UPDATE SKIP LOCKED} + 租约；无需额外分布式调度器。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "wgh.scheduler", name = "enabled", havingValue = "true", matchIfMissing = true)
public class JobDispatcher {

    private final SchedulerProperties properties;

    private final JobStore jobStore;

    private final JobRunner jobRunner;

    private final LaneExecutors laneExecutors;

    private final SchedulerIdentity identity;

    private final SchedulerInstanceRegistry instanceRegistry;

    private final SchedulerMetrics metrics;

    private final ChatRunProperties chatRunProperties;

    /** 周期维护作业声明（无声明时为空列表，调度层不额外提交）。 */
    private final List<RecurringJob> recurringJobs;

    /** 本实例最近一次提交的周期桶；仅用于减少重复提交，正确性由作业表唯一键保证。 */
    private final Map<String, Long> recurringBuckets = new ConcurrentHashMap<>();

    private final ScheduledExecutorService loop = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "sched-dispatcher");
        thread.setDaemon(true);
        return thread;
    });

    private final AtomicBoolean dispatching = new AtomicBoolean();

    private final AtomicBoolean databaseHealthy = new AtomicBoolean(true);

    public JobDispatcher(SchedulerProperties properties,
                         JobStore jobStore,
                         JobRunner jobRunner,
                         LaneExecutors laneExecutors,
                         SchedulerIdentity identity,
                         SchedulerInstanceRegistry instanceRegistry,
                         SchedulerMetrics metrics,
                         ChatRunProperties chatRunProperties,
                         List<RecurringJob> recurringJobs) {
        this.properties = properties;
        this.jobStore = jobStore;
        this.jobRunner = jobRunner;
        this.laneExecutors = laneExecutors;
        this.identity = identity;
        this.instanceRegistry = instanceRegistry;
        this.metrics = metrics;
        this.chatRunProperties = chatRunProperties;
        this.recurringJobs = recurringJobs == null ? List.of() : List.copyOf(recurringJobs);
    }

    @PostConstruct
    public void start() {
        // 同 instance-id 重启时，上一代进程可能已异常退出但租约尚未到期；启动即接管其残留作业。
        reclaimOwnStale();
        long claimMillis = Math.max(200L, properties.getClaimInterval().toMillis());
        long reclaimMillis = Math.max(1000L, properties.getReclaimInterval().toMillis());
        long queueMillis = Math.max(1000L, properties.getQueueSweepInterval().toMillis());
        long instanceMillis = Math.max(1000L, properties.getInstanceSweepInterval().toMillis());
        loop.scheduleWithFixedDelay(this::dispatchSafely, 0, claimMillis, TimeUnit.MILLISECONDS);
        loop.scheduleWithFixedDelay(this::reclaimSafely, reclaimMillis, reclaimMillis, TimeUnit.MILLISECONDS);
        loop.scheduleWithFixedDelay(this::requeueSafely, claimMillis, claimMillis, TimeUnit.MILLISECONDS);
        loop.scheduleWithFixedDelay(this::expireQueuedSafely, queueMillis, queueMillis, TimeUnit.MILLISECONDS);
        loop.scheduleWithFixedDelay(this::reclaimDeadInstancesSafely, 5_000L, instanceMillis,
                TimeUnit.MILLISECONDS);
        long purgeMillis = Math.max(3_600_000L, properties.getInstanceRetention().toMillis() / 4);
        loop.scheduleWithFixedDelay(this::purgeInstancesSafely, purgeMillis, purgeMillis,
                TimeUnit.MILLISECONDS);
        loop.scheduleWithFixedDelay(this::purgeChatRunsSafely, purgeMillis, purgeMillis,
                TimeUnit.MILLISECONDS);
        if (!recurringJobs.isEmpty()) {
            long recurringMillis = recurringCheckMillis();
            loop.scheduleWithFixedDelay(this::submitRecurringSafely, 2_000L, recurringMillis,
                    TimeUnit.MILLISECONDS);
            log.info("周期维护作业已登记 {} 个，检查间隔 {}ms", recurringJobs.size(), recurringMillis);
        }
        log.info("调度中心已启动 instanceId={} 车道={}", identity.instanceId(), laneExecutors.snapshots());
    }

    /**
     * 周期作业检查间隔：取最短提交间隔的一半，限制在 [5s, 60s]。
     */
    private long recurringCheckMillis() {
        long minMillis = recurringJobs.stream()
                .map(RecurringJob::interval)
                .filter(Objects::nonNull)
                .mapToLong(Duration::toMillis)
                .filter(value -> value > 0)
                .min()
                .orElse(60_000L);
        return Math.max(5_000L, Math.min(minMillis / 2, 60_000L));
    }

    /** 按时间分桶提交周期维护作业；同桶重复提交由作业表唯一键去重。 */
    private void submitRecurringSafely() {
        for (RecurringJob job : recurringJobs) {
            try {
                submitRecurring(job);
            } catch (Exception e) {
                log.warn("周期维护作业提交失败 jobType={} error={}",
                        job == null ? null : job.jobType(), e.getMessage());
            }
        }
    }

    private void submitRecurring(RecurringJob job) {
        if (job == null || job.jobType() == null || job.jobType().isBlank()) {
            return;
        }
        Duration interval = job.interval();
        long intervalMillis = interval == null || interval.isZero() || interval.isNegative()
                ? 60_000L : interval.toMillis();
        long bucket = System.currentTimeMillis() / intervalMillis;
        Long previous = recurringBuckets.get(job.jobType());
        if (previous != null && previous == bucket) {
            return;
        }
        ExecutionRequest request = ExecutionRequest.builder(job.jobType(), job.lane())
                .bizKey(job.jobType() + ":recurring:" + bucket)
                .payload(job.payload())
                .priority(job.priority())
                .build();
        ExecutionJob submitted = jobStore.submit(request, properties.getMaxAttempts(), null);
        recurringBuckets.put(job.jobType(), bucket);
        log.debug("周期维护作业已提交 jobType={} executionId={} bucket={}",
                job.jobType(), submitted.getExecutionId(), bucket);
    }

    /** 提交后触发一次即时领取，降低提交到执行的延迟。 */
    public void kick() {
        if (!loop.isShutdown()) {
            loop.execute(this::dispatchSafely);
        }
    }

    private void dispatchSafely() {
        if (!dispatching.compareAndSet(false, true)) {
            return;
        }
        try {
            dispatch(ExecutionLane.BACKGROUND);
            dispatch(ExecutionLane.MAINTENANCE);
            markDatabaseHealthy();
        } catch (Exception e) {
            markDatabaseUnavailable("claim", "领取作业失败", e);
        } finally {
            dispatching.set(false);
        }
    }

    private void dispatch(ExecutionLane lane) {
        while (!Thread.currentThread().isInterrupted()) {
            if (!laneExecutors.tryReserve(lane)) {
                return;
            }
            Optional<ExecutionJob> claimed = jobStore.claim(lane, identity.instanceId(), properties.getLeaseTtl());
            if (claimed.isEmpty()) {
                laneExecutors.release(lane);
                return;
            }
            ExecutionJob job = claimed.get();
            try {
                laneExecutors.executor(lane).execute(() -> {
                    try {
                        // 恢复提交时的 traceId，执行日志可与提交请求按 trace 关联（跨实例同样有效）
                        String previousTraceId = MdcContext.putTraceId(job.getTraceId());
                        try {
                            jobRunner.run(job);
                        } finally {
                            MdcContext.restoreTraceId(previousTraceId);
                        }
                    } finally {
                        laneExecutors.release(lane);
                    }
                });
            } catch (RejectedExecutionException e) {
                laneExecutors.release(lane);
                jobStore.releaseClaim(job.getId(), job.getLeaseOwner(), properties.getSingleFlight().getRetryDelay());
                log.warn("车道线程池拒绝作业，已放回队列 executionId={}", job.getExecutionId());
                return;
            }
        }
    }

    private void reclaimSafely() {
        try {
            int reclaimed = jobStore.reclaimExpired();
            if (reclaimed > 0) {
                metrics.recordLeaseExpired(reclaimed);
                log.warn("回收租约过期作业 {} 条（可能为进程异常退出）", reclaimed);
                kick();
            }
            markDatabaseHealthy();
        } catch (Exception e) {
            markDatabaseUnavailable("reclaim", "回收租约过期作业失败", e);
        }
    }

    /**
     * 启动时接管同 instance-id 的残留作业。
     *
     * <p>实例标识缺省为 {@code HOSTNAME-pid}；容器重启后 pid 仍为 1、HOSTNAME 不变时，
     * 新一代进程必须接管上一代未完成的作业，否则要等租约自然到期。
     */
    private void reclaimOwnStale() {
        try {
            int reclaimed = jobStore.reclaimDeadOwner(identity.instanceId());
            if (reclaimed > 0) {
                metrics.recordInstanceReclaimed(reclaimed);
                log.warn("启动接管同实例标识的历史作业 {} 条 instanceId={}", reclaimed, identity.instanceId());
            }
        } catch (Exception e) {
            markDatabaseUnavailable("dead-instance", "启动接管历史作业失败", e);
        }
    }

    /**
     * 失联实例提前回收：实例心跳过期或已主动下线时，立即回收其 {@code LEASED/RUNNING} 作业。
     *
     * <p>不再等待 {@code lease_expire_time}，把「实例已死但租约未到期」的悬空窗口从最长一个租约周期
     * 缩短到存活阈值 + 扫描间隔。旧实例即便仍在运行，其续租与终态写入都会因 {@code lease_owner}
     * 已清空而失败，不会覆盖接管者。
     */
    private void reclaimDeadInstancesSafely() {
        try {
            List<SchedulerInstance> deadInstances = instanceRegistry.deadInstances();
            for (SchedulerInstance instance : deadInstances) {
                int reclaimed = jobStore.reclaimDeadOwner(instance.getInstanceId());
                if (reclaimed > 0) {
                    metrics.recordInstanceReclaimed(reclaimed);
                    log.warn("失联实例作业已提前回收 instanceId={} count={}", instance.getInstanceId(), reclaimed);
                }
                if (instance.getStoppedAt() == null) {
                    metrics.recordDeadDetected(instance.getInstanceId());
                    instanceRegistry.markStopped(instance.getInstanceId());
                    log.warn("检测到失联实例并标记下线 instanceId={} lastHeartbeat={}",
                            instance.getInstanceId(), instance.getLastHeartbeat());
                }
                if (reclaimed > 0) {
                    kick();
                }
            }
            markDatabaseHealthy();
        } catch (Exception e) {
            markDatabaseUnavailable("dead-instance", "失联实例作业回收失败", e);
        }
    }

    private void purgeInstancesSafely() {
        try {
            int purged = instanceRegistry.purgeStaleInstances();
            if (purged > 0) {
                log.info("清理过期调度实例行 {} 条", purged);
            }
        } catch (Exception e) {
            markDatabaseUnavailable("purge-instance", "清理过期调度实例行失败", e);
        }
    }

    /** 清理崩溃残留的终态对话运行态记录（正常运行结束的记录在注销时已物理删除）。 */
    private void purgeChatRunsSafely() {
        try {
            int purged = jobStore.purgeTerminalInteractiveRuns(chatRunProperties.getTerminalRetention());
            if (purged > 0) {
                log.info("清理终态对话运行态记录 {} 条", purged);
            }
        } catch (Exception e) {
            markDatabaseUnavailable("purge-chat-run", "清理终态对话运行态记录失败", e);
        }
    }

    private void requeueSafely() {
        try {
            int requeued = jobStore.requeueFailed();
            if (requeued > 0) {
                log.info("可重试失败作业已放回队列 {} 条", requeued);
            }
            markDatabaseHealthy();
        } catch (Exception e) {
            markDatabaseUnavailable("requeue", "重试作业入队失败", e);
        }
    }

    private void expireQueuedSafely() {
        try {
            int expired = jobStore.expireQueued(properties.getQueueTimeout());
            if (expired > 0) {
                log.warn("排队超时作业已落终态 {} 条", expired);
            }
            markDatabaseHealthy();
        } catch (Exception e) {
            markDatabaseUnavailable("expire", "排队超时扫描失败", e);
        }
    }

    private void markDatabaseHealthy() {
        if (databaseHealthy.compareAndSet(false, true)) {
            log.info("调度中心数据库访问已恢复");
        }
    }

    /** 数据库不可用时只记录一次日志，但每次失败都计指标，避免调度循环每 2 秒刷屏。 */
    private void markDatabaseUnavailable(String op, String action, Exception error) {
        metrics.recordDispatchError(op);
        if (databaseHealthy.compareAndSet(true, false)) {
            log.error("{}；调度循环将在下个周期重试（作业保持 PENDING）: {}",
                    action, error.getMessage(), error);
        } else {
            log.debug("{}（仍在故障中）: {}", action, error.getMessage());
        }
    }

    /** 供运行时快照使用。 */
    @PreDestroy
    public void stop() {
        loop.shutdownNow();
    }
}
