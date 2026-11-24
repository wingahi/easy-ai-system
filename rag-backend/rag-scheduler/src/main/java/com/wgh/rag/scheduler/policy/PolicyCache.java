package com.wgh.rag.scheduler.policy;

import com.wgh.rag.execution.model.ExecutionLane;
import com.wgh.rag.scheduler.config.SchedulerProperties;
import com.wgh.rag.scheduler.entity.TenantSchedulePolicy;
import com.wgh.rag.scheduler.mapper.TenantSchedulePolicyMapper;
import com.wgh.rag.scheduler.metrics.SchedulerMetrics;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 租户策略本地缓存。
 *
 * <p>请求路径只读本地缓存，绝不查库；每 {@code wgh.scheduler.policy-refresh-interval} 全量重载一次，
 * 管理端更新后主动 {@link #reload()}。策略表不可用时保留上一份快照并回退代码默认值，
 * 不让策略读取故障阻断准入（准入本身仍有全局车道上限兜底）。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "wgh.scheduler", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PolicyCache {

    private final SchedulerProperties properties;

    private final TenantSchedulePolicyMapper mapper;

    private final ObjectProvider<SchedulerMetrics> metrics;

    /** tenantId → lane → 覆盖策略；{@code enabled=0} 的行不放入，按默认策略处理。 */
    private final Map<String, Map<ExecutionLane, EffectiveSchedulePolicy>> overrides = new ConcurrentHashMap<>();

    private final AtomicBoolean cacheHealthy = new AtomicBoolean(true);

    private final ScheduledExecutorService loop = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "sched-policy-cache");
        thread.setDaemon(true);
        return thread;
    });

    public PolicyCache(SchedulerProperties properties,
                       TenantSchedulePolicyMapper mapper,
                       ObjectProvider<SchedulerMetrics> metrics) {
        this.properties = properties;
        this.mapper = mapper;
        this.metrics = metrics;
    }

    @PostConstruct
    public void start() {
        refreshSafely();
        long intervalMillis = Math.max(5_000L, properties.getPolicyRefreshInterval().toMillis());
        loop.scheduleWithFixedDelay(this::refreshSafely, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void stop() {
        loop.shutdownNow();
    }

    /**
     * 解析生效策略。
     *
     * @param tenantId 租户标识，为空时使用匿名桶
     * @param lane     执行车道
     * @return 策略表覆盖值；无覆盖或策略关闭时返回代码默认值
     */
    public EffectiveSchedulePolicy resolve(String tenantId, ExecutionLane lane) {
        String key = normalize(tenantId);
        Map<ExecutionLane, EffectiveSchedulePolicy> lanes = overrides.get(key);
        EffectiveSchedulePolicy policy = lanes == null ? null : lanes.get(lane);
        return policy != null ? policy : EffectiveSchedulePolicy.defaults(key, lane, properties);
    }

    /** 匿名桶归一：空租户统一落到 {@code wgh.scheduler.anonymous-tenant-id}。 */
    public String normalize(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            String anonymous = properties.getAnonymousTenantId();
            return anonymous == null || anonymous.isBlank() ? "default" : anonymous;
        }
        return tenantId.trim();
    }

    /** 管理端写入后主动重载，避免等待下一个刷新周期。 */
    public void reload() {
        refreshSafely();
    }

    /** 当前缓存覆盖条数（管理端/日志观测用）。 */
    public int cachedPolicyCount() {
        return overrides.values().stream().mapToInt(Map::size).sum();
    }

    private void refreshSafely() {
        try {
            List<TenantSchedulePolicy> rows = mapper.selectAllPolicies();
            Map<String, Map<ExecutionLane, EffectiveSchedulePolicy>> loaded = new ConcurrentHashMap<>();
            int count = 0;
            for (TenantSchedulePolicy row : rows) {
                if (row.getLane() == null || row.getTenantId() == null || row.getTenantId().isBlank()) {
                    continue;
                }
                if (row.getEnabled() != null && row.getEnabled() == 0) {
                    continue;
                }
                EffectiveSchedulePolicy defaults = EffectiveSchedulePolicy.defaults(
                        row.getTenantId(), row.getLane(), properties);
                loaded.computeIfAbsent(row.getTenantId(), key -> new EnumMap<>(ExecutionLane.class))
                        .put(row.getLane(), EffectiveSchedulePolicy.from(row, defaults));
                count++;
            }
            overrides.clear();
            overrides.putAll(loaded);
            if (cacheHealthy.compareAndSet(false, true)) {
                log.info("租户调度策略缓存已恢复 条数={}", count);
            }
            log.debug("租户调度策略缓存已刷新 条数={}", count);
        } catch (Exception e) {
            SchedulerMetrics schedulerMetrics = metrics.getIfAvailable();
            if (schedulerMetrics != null) {
                schedulerMetrics.recordPolicyLoadFailure();
            }
            if (cacheHealthy.compareAndSet(true, false)) {
                log.warn("租户调度策略加载失败，继续使用上一份快照/默认策略: {}"
                        + "（请确认已执行 sql/migrations/20261124-01/04_tenant_schedule_policy.sql）", e.getMessage());
            } else {
                log.debug("租户调度策略仍在加载失败: {}", e.getMessage());
            }
        }
    }
}
