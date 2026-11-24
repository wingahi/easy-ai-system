package com.wgh.rag.kb.storage;

import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.data.tenant.PlatformScanTemplate;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.function.BooleanSupplier;

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
    private final PlatformScanTemplate platformScan;

    /** 调度中心开启时由 {@code KB_ORPHAN_CLEANUP} 周期作业接管，本地定时器不启动。 */
    @Value("${wgh.scheduler.enabled:true}")
    private boolean schedulerEnabled = true;

    private ThreadPoolTaskScheduler scheduler;

    @PostConstruct
    void start() {
        if (!properties.isEnabled()) {
            log.info("孤儿对象定期扫描未启用（wgh.rag.storage.orphan-cleanup.enabled=false）");
            return;
        }
        if (schedulerEnabled) {
            log.info("孤儿对象定期扫描已交由调度中心（KB_ORPHAN_CLEANUP），本地定时器不启动");
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

    /** 执行一轮扫描；调度中心周期作业与本地定时器共用同一入口（不感知取消）。 */
    public void runOnce() {
        runOnce(() -> false);
    }

    /**
     * 按租户扫描孤儿对象。
     *
     * <p>孤儿对象只按租户前缀列举，因此必须逐租户执行；<b>包含停用租户</b>——
     * 停用租户的历史对象仍需回收。锁名带上租户后，多实例可以并行处理不同租户。
     */
    public void runOnce(BooleanSupplier cancelled) {
        platformScan.scan(true, cancelled, tenantId -> {
            try (StorageMaintenanceLock.Handle handle =
                         maintenanceLock.tryAcquire(LOCK_NAME + ":" + tenantId)) {
                if (handle == null) {
                    log.info("孤儿对象扫描跳过：其他实例正在执行 tenant={}", tenantId);
                    return PlatformScanTemplate.TenantCounters.EMPTY;
                }
                cleanupService.scan(tenantId, properties.isDelete());
                return new PlatformScanTemplate.TenantCounters(1, 0);
            } catch (BusinessException e) {
                // 同一实例内已有扫描在跑（进程内互斥），本轮跳过该租户即可
                log.debug("孤儿对象扫描跳过 tenant={}: {}", tenantId, e.getMessage());
                return PlatformScanTemplate.TenantCounters.EMPTY;
            }
        });
    }
}
