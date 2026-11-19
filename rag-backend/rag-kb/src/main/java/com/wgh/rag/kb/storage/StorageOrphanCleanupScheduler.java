package com.wgh.rag.kb.storage;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * 孤儿对象定期扫描调度器。
 * <p>
 * 默认启用但只有报告（delete=false）；首次执行在启动一个扫描间隔之后，避免启动期集中扫描。
 * 同一时刻只允许一个实例执行：通过 {@link StorageMaintenanceLock} 的 advisory lock 保证多实例单飞。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StorageOrphanCleanupScheduler {

    private static final String LOCK_NAME = "wgh.storage.orphan-cleanup";

    private final StorageOrphanCleanupService cleanupService;
    private final StorageOrphanCleanupProperties properties;
    private final StorageMaintenanceLock maintenanceLock;

    private ThreadPoolTaskScheduler scheduler;

    @PostConstruct
    void start() {
        if (!properties.isEnabled()) {
            log.info("孤儿对象定期扫描未启用（wgh.rag.storage.orphan-cleanup.enabled=false）");
            return;
        }
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("storage-orphan-cleanup-");
        scheduler.initialize();
        int interval = Math.max(5, properties.getIntervalMinutes());
        scheduler.scheduleWithFixedDelay(this::runOnce,
                Instant.now().plus(Duration.ofMinutes(interval)),
                Duration.ofMinutes(interval));
        log.info("孤儿对象定期扫描已启用：间隔 {} 分钟，删除开关 {}，删除保护期 {} 分钟",
                interval, properties.isDelete(), Math.max(0, properties.getMinAgeMinutes()));
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    void runOnce() {
        try (StorageMaintenanceLock.Handle handle = maintenanceLock.tryAcquire(LOCK_NAME)) {
            if (handle == null) {
                log.info("孤儿对象扫描跳过：其他实例正在执行");
                return;
            }
            cleanupService.scan(properties.isDelete());
        } catch (RuntimeException e) {
            log.warn("孤儿对象定期扫描任务异常: {}", e.getMessage(), e);
        }
    }
}
