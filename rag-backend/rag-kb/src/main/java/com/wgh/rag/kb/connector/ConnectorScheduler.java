package com.wgh.rag.kb.connector;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wgh.rag.kb.entity.KbConnector;
import com.wgh.rag.kb.mapper.KbConnectorMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

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

    private ThreadPoolTaskScheduler scheduler;

    @PostConstruct
    void start() {
        if (!properties.isSchedulerEnabled()) {
            log.info("URL 连接器定时同步未启用（wgh.connector.scheduler.enabled=false），仅支持手动同步");
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

    /** 单轮轮询：只挑到期的启用连接器，单个失败不影响本轮其他连接器。 */
    void tick() {
        try {
            LocalDateTime now = LocalDateTime.now();
            int batchSize = Math.max(1, properties.getBatchSize());
            List<KbConnector> candidates = connectorMapper.selectList(new LambdaQueryWrapper<KbConnector>()
                    .eq(KbConnector::getEnabled, 1)
                    .orderByAsc(KbConnector::getLastSyncTime)
                    .last("LIMIT " + batchSize));

            int triggered = 0;
            for (KbConnector connector : candidates) {
                if (!isDue(connector, now, properties.getDefaultSyncIntervalMinutes())) {
                    continue;
                }
                connectorService.syncScheduled(connector.getId());
                triggered++;
            }
            if (triggered > 0) {
                log.info("URL 连接器定时同步本轮触发 {} 个（候选 {} 个）", triggered, candidates.size());
            }
        } catch (Exception e) {
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
