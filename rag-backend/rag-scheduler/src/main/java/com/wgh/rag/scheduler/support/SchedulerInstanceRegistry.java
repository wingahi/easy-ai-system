package com.wgh.rag.scheduler.support;

import com.wgh.rag.data.tenant.DatabaseRoleTemplate;
import com.wgh.rag.scheduler.config.SchedulerProperties;
import com.wgh.rag.scheduler.entity.SchedulerInstance;
import com.wgh.rag.scheduler.job.JobPayloadCodec;
import com.wgh.rag.scheduler.lane.LaneExecutors;
import com.wgh.rag.scheduler.lane.LaneRuntimeSnapshot;
import com.wgh.rag.scheduler.mapper.SchedulerInstanceMapper;
import com.wgh.rag.scheduler.metrics.SchedulerMetrics;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 调度实例注册与心跳。
 *
 * <p>每个实例启动时向 {@code wgh_scheduler_instance} 注册，随后按
 * {@code wgh.scheduler.instance-heartbeat-interval} 心跳；其它实例据此判断存活：
 * 心跳超过 {@code instance-liveness-ttl} 即视为失联，其持有的 {@code LEASED/RUNNING}
 * 作业会被提前回收，不再等待租约自然到期。
 *
 * <p>失败语义：注册/心跳失败只影响「提前回收」能力，不影响作业领取与租约保护；
 * 因此这里记错误日志 + 指标后继续重试，不阻断调度。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "wgh.scheduler", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SchedulerInstanceRegistry {

    private static final String SCHEDULER_ROLE = "wgh_scheduler";

    private final SchedulerProperties properties;

    private final SchedulerIdentity identity;

    private final SchedulerInstanceMapper mapper;

    private final LaneExecutors laneExecutors;

    private final JobPayloadCodec codec;

    private final SchedulerMetrics metrics;

    /** 实例表跨租户读写：独立事务内 SET LOCAL ROLE wgh_scheduler */
    private final DatabaseRoleTemplate roles;

    private final AtomicBoolean registryHealthy = new AtomicBoolean(true);

    private final ScheduledExecutorService heartbeatLoop = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "sched-instance-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    public SchedulerInstanceRegistry(SchedulerProperties properties,
                                     SchedulerIdentity identity,
                                     SchedulerInstanceMapper mapper,
                                     LaneExecutors laneExecutors,
                                     JobPayloadCodec codec,
                                     SchedulerMetrics metrics,
                                     DatabaseRoleTemplate roles) {
        this.properties = properties;
        this.identity = identity;
        this.mapper = mapper;
        this.laneExecutors = laneExecutors;
        this.codec = codec;
        this.metrics = metrics;
        this.roles = roles;
    }

    @PostConstruct
    public void start() {
        register();
        long intervalMillis = Math.max(1000L, properties.getInstanceHeartbeatInterval().toMillis());
        heartbeatLoop.scheduleWithFixedDelay(this::heartbeatSafely, intervalMillis, intervalMillis,
                TimeUnit.MILLISECONDS);
    }

    /** 当前实例标识。 */
    public String instanceId() {
        return identity.instanceId();
    }

    /** 当前实例是否已成功注册（失联回收依赖该状态）。 */
    public boolean registered() {
        return registryHealthy.get();
    }

    /** 失联实例列表（排除当前实例），供调度循环提前回收其作业。 */
    public List<SchedulerInstance> deadInstances() {
        long livenessSeconds = Math.max(1L, properties.getInstanceLivenessTtl().toSeconds());
        return roles.runReadOnlyAs(SCHEDULER_ROLE,
                () -> mapper.selectDeadInstances(identity.instanceId(), livenessSeconds));
    }

    /** 把实例标记为已下线，避免每轮扫描重复处理同一实例。 */
    public void markStopped(String instanceId) {
        roles.runAs(SCHEDULER_ROLE, () -> mapper.markStopped(instanceId));
    }

    /** 清理历史实例行（由调度循环低频调用）。 */
    public int purgeStaleInstances() {
        long retentionSeconds = Math.max(60L, properties.getInstanceRetention().toSeconds());
        return roles.runAs(SCHEDULER_ROLE, () -> mapper.purgeStale(retentionSeconds));
    }

    @PreDestroy
    public void stop() {
        heartbeatLoop.shutdownNow();
        // 不在这里 markStopped：Bean 销毁顺序可能早于车道线程池排水，
        // 过早标记下线会让其它实例在本地作业仍在收尾时提前回收。
        // 停机后心跳自然过期（instance-liveness-ttl），由其它实例安全接管。
        log.info("调度实例心跳已停止 instanceId={}", identity.instanceId());
    }

    private void register() {
        try {
            roles.runAs(SCHEDULER_ROLE, () -> mapper.register(identity.instanceId(), lanesJson()));
            registryHealthy.set(true);
            log.info("调度实例已注册 instanceId={} 心跳间隔={} 存活阈值={}",
                    identity.instanceId(), properties.getInstanceHeartbeatInterval(),
                    properties.getInstanceLivenessTtl());
        } catch (Exception e) {
            registryHealthy.set(false);
            metrics.recordHeartbeatFailure();
            log.error("调度实例注册失败（不影响租约保护，将按心跳周期重试；"
                    + "请确认已执行 sql/migrations/20261124/01_scheduler_instance.sql） instanceId={} error={}",
                    identity.instanceId(), e.getMessage(), e);
        }
    }

    private void heartbeatSafely() {
        try {
            if (registryHealthy.get()) {
                int updated = roles.runAs(SCHEDULER_ROLE,
                        () -> mapper.heartbeat(identity.instanceId(), lanesJson()));
                if (updated == 0) {
                    // 行被清理或首次注册失败：重新注册
                    register();
                }
            } else {
                register();
            }
        } catch (Exception e) {
            metrics.recordHeartbeatFailure();
            if (registryHealthy.compareAndSet(true, false)) {
                log.error("调度实例心跳失败，失联判定暂不可用（作业仍受租约保护） instanceId={} error={}",
                        identity.instanceId(), e.getMessage(), e);
            } else {
                log.debug("调度实例心跳仍在失败 instanceId={} error={}", identity.instanceId(), e.getMessage());
            }
        }
    }

    /** 车道快照 → JSON 对象；只用于管理端观测，不参与协调。 */
    private String lanesJson() {
        Map<String, Object> lanes = new LinkedHashMap<>();
        for (LaneRuntimeSnapshot snapshot : laneExecutors.snapshots()) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("inFlight", snapshot.inFlight());
            value.put("queued", snapshot.queued());
            value.put("poolSize", snapshot.poolSize());
            value.put("maxConcurrent", snapshot.maxConcurrent());
            value.put("queueCapacity", snapshot.queueCapacity());
            lanes.put(snapshot.lane().name(), value);
        }
        return codec.toJson(lanes);
    }
}
