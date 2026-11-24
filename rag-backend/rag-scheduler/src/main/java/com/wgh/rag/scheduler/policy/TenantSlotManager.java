package com.wgh.rag.scheduler.policy;

import com.wgh.rag.execution.model.ExecutionLane;
import com.wgh.rag.scheduler.config.SchedulerProperties;
import com.wgh.rag.scheduler.mapper.TenantRuntimeSlotMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 租户集群运行槽管理。
 *
 * <p>后台作业开始执行前原子占槽，结束后释放；周期性用作业表实际在跑数校正槽位，
 * 抵消进程崩溃造成的泄漏，并暴露 {@code wgh.scheduler.slot.drift} 指标。
 *
 * <p>槽位表不可用时按「失败开放」处理：只记录一次告警后放行执行，
 * 租户并发仍受准入侧的作业表计数约束，不让观测组件故障阻断业务。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "wgh.scheduler", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TenantSlotManager {

    /** 槽位与作业表实际在跑数的偏差（绝对值之和）。 */
    public static final String SLOT_DRIFT = "wgh.scheduler.slot.drift";

    private final SchedulerProperties properties;

    private final TenantRuntimeSlotMapper mapper;

    private final AtomicLong drift = new AtomicLong();

    private final AtomicBoolean slotHealthy = new AtomicBoolean(true);

    private final ScheduledExecutorService loop = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "sched-slot-reconcile");
        thread.setDaemon(true);
        return thread;
    });

    public TenantSlotManager(SchedulerProperties properties,
                             TenantRuntimeSlotMapper mapper,
                             MeterRegistry meterRegistry) {
        this.properties = properties;
        this.mapper = mapper;
        Gauge.builder(SLOT_DRIFT, drift, AtomicLong::get).register(meterRegistry);
    }

    @PostConstruct
    public void start() {
        long intervalMillis = Math.max(30_000L, properties.getSlotReconcileInterval().toMillis());
        loop.scheduleWithFixedDelay(this::reconcileSafely, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void stop() {
        loop.shutdownNow();
    }

    /**
     * 尝试占一个集群槽位。
     *
     * @return {@code true} 表示可执行；{@code false} 表示集群内该租户车道已满
     */
    public boolean tryAcquire(String tenantId, ExecutionLane lane, int limit) {
        try {
            boolean acquired = mapper.tryAcquire(tenantId, lane.name(), Math.max(1, limit)) > 0;
            if (acquired) {
                markHealthy();
            }
            return acquired;
        } catch (Exception e) {
            markUnavailable("占槽", tenantId, lane, e);
            return true;
        }
    }

    /** 释放槽位；异常只告警，由下轮对账校正。 */
    public void release(String tenantId, ExecutionLane lane) {
        try {
            mapper.release(tenantId, lane.name());
            markHealthy();
        } catch (Exception e) {
            markUnavailable("释放槽位", tenantId, lane, e);
        }
    }

    /** 当前漂移值（管理端/测试观测用）。 */
    public long drift() {
        return drift.get();
    }

    /** 立即执行一次对账（管理端可触发）。 */
    public void reconcileNow() {
        reconcileSafely();
    }

    private void reconcileSafely() {
        try {
            drift.set(mapper.currentDrift());
            mapper.refreshFromJobs();
            mapper.zeroIdleSlots();
            markHealthy();
            if (drift.get() > 0) {
                log.warn("租户运行槽与作业表存在偏差，已按作业表校正 drift={}", drift.get());
            }
        } catch (Exception e) {
            drift.set(0);
            markUnavailable("对账", "-", null, e);
        }
    }

    private void markHealthy() {
        if (slotHealthy.compareAndSet(false, true)) {
            log.info("租户运行槽已恢复");
        }
    }

    private void markUnavailable(String action, String tenantId, ExecutionLane lane, Exception error) {
        if (slotHealthy.compareAndSet(true, false)) {
            log.warn("租户运行槽{}失败，已失败开放；请确认执行 sql/migrations/20261124-01/04_tenant_schedule_policy.sql "
                    + "tenantId={} lane={} error={}", action, tenantId, lane, error.getMessage());
        } else {
            log.debug("租户运行槽{}仍在失败 tenantId={} lane={}: {}", action, tenantId, lane, error.getMessage());
        }
    }
}
