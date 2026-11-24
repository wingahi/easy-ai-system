package com.wgh.rag.kb.connector;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wgh.rag.data.tenant.PlatformScanTemplate;
import com.wgh.rag.kb.entity.KbConnector;
import com.wgh.rag.kb.mapper.KbConnectorMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * URL 数据源定时同步调度器。
 *
 * <p>默认关闭：只有显式配置 {@code wgh.connector.scheduler.enabled=true} 才会注册轮询，
 * 避免服务启动后无条件抓取外网。每轮只处理到期的启用连接器（间隔为 0 表示仅手动同步）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConnectorScheduler {

    private final KbConnectorMapper connectorMapper;
    private final ConnectorService connectorService;
    private final ConnectorProperties properties;
    private final PlatformScanTemplate platformScan;

    /** 调度中心开启时由 {@code KB_CONNECTOR_SYNC} 周期作业接管，本地定时器不启动。 */
    @Value("${wgh.scheduler.enabled:true}")
    private boolean schedulerEnabled = true;

    private ThreadPoolTaskScheduler scheduler;

    @PostConstruct
    void start() {
        if (!properties.isSchedulerEnabled()) {
            log.info("URL 连接器定时同步未启用（wgh.connector.scheduler.enabled=false），仅支持手动同步");
            return;
        }
        if (schedulerEnabled) {
            log.info("URL 连接器定时同步已交由调度中心（KB_CONNECTOR_SYNC），本地定时器不启动");
            return;
        }
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("kb-connector-");
        scheduler.initialize();
        int interval = Math.max(5, properties.getSchedulerIntervalSeconds());
        scheduler.scheduleWithFixedDelay(this::tick,
                java.time.Instant.now().plusSeconds(Math.max(1, properties.getInitialDelaySeconds())),
                Duration.ofSeconds(interval));
        log.info("URL 连接器定时同步已启用，轮询间隔 {}s", interval);
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    /** 本地定时器入口：不感知作业取消。 */
    public void tick() {
        tick(() -> false);
    }

    /**
     * 单轮轮询：按租户枚举到期的启用连接器，单个连接器失败不影响本轮其他连接器。
     *
     * <p>原来只按作业的默认租户扫描，多租户下其他租户的连接器永远不会被同步；
     * 现在由 {@link PlatformScanTemplate} 逐租户下发租户上下文后再查询与同步。
     *
     * @param cancelled 取消探测
     */
    public void tick(BooleanSupplier cancelled) {
        try {
            AtomicInteger triggered = new AtomicInteger();
            platformScan.scan(false, cancelled, tenantId -> {
                LocalDateTime now = LocalDateTime.now();
                int batchSize = Math.max(1, properties.getBatchSize());
                // 读连接器表必须在租户事务内下发 app.tenant_id，否则切换 wgh_app 后 RLS 会静默返回 0 行
                List<KbConnector> candidates = platformScan.read(tenantId,
                        () -> connectorMapper.selectList(new LambdaQueryWrapper<KbConnector>()
                                .eq(KbConnector::getEnabled, 1)
                                .orderByAsc(KbConnector::getLastSyncTime)
                                .last("LIMIT " + batchSize)));

                long due = 0;
                long failed = 0;
                for (KbConnector connector : candidates) {
                    if (!isDue(connector, now, properties.getDefaultSyncIntervalMinutes())) {
                        continue;
                    }
                    due++;
                    try {
                        connectorService.syncScheduled(connector.getId());
                        triggered.incrementAndGet();
                    } catch (RuntimeException e) {
                        failed++;
                        log.warn("[ALERT] 连接器定时同步失败，已跳过 tenant={} connectorId={}",
                                tenantId, connector.getId(), e);
                    }
                }
                return new PlatformScanTemplate.TenantCounters(due, failed);
            });
            if (triggered.get() > 0) {
                log.info("URL 连接器定时同步本轮触发 {} 个", triggered.get());
            }
        } catch (RuntimeException e) {
            log.error("URL 连接器定时同步轮询失败", e);
        }
    }

    /**
     * 到期判定：启用 + 间隔大于 0 + 从未同步或已超过同步间隔。
     */
    static boolean isDue(KbConnector connector, LocalDateTime now, int defaultIntervalMinutes) {
        if (connector == null || connector.getEnabled() == null || connector.getEnabled() != 1) {
            return false;
        }
        int interval = connector.getSyncIntervalMinutes() == null
                ? defaultIntervalMinutes : connector.getSyncIntervalMinutes();
        if (interval <= 0) {
            return false;
        }
        LocalDateTime lastSync = connector.getLastSyncTime();
        return lastSync == null || !lastSync.plusMinutes(interval).isAfter(now);
    }
}
