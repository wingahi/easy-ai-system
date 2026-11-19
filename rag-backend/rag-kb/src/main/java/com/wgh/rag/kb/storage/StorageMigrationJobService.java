package com.wgh.rag.kb.storage;

import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.metrics.RagMetrics;
import com.wgh.rag.config.redis.RedisDistributedLock;
import com.wgh.rag.config.redis.RedisUnavailableException;
import com.wgh.rag.storage.migration.MigrationOutcome;
import com.wgh.rag.storage.migration.MigrationProgressListener;
import com.wgh.rag.storage.migration.MigrationReport;
import com.wgh.rag.storage.migration.StorageMigrationService;
import com.wgh.rag.storage.service.StorageObjectInfo;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 存储迁移异步作业。
 *
 * <p><b>跨实例状态</b>：任务状态优先写 Redis（见 {@link MigrationJobStateStore}），任意实例都能按 taskId
 * 查询；Redis 不可用时自动退回进程内状态，并在首次降级时输出 WARN + {@code wgh.state.store.fallbacks}。</p>
 *
 * <p><b>跨实例单飞</b>：使用 {@link RedisDistributedLock} 的 {@code SET NX PX} 原子加锁，锁值含实例唯一 owner，
 * 释放时 Lua 校验 owner，避免误删其他实例的锁；执行期间按配置间隔续约。
 * <b>Redis 不可用时拒绝启动新迁移，不回退进程内锁</b>。</p>
 *
 * <p><b>进度写入节流</b>：每 {@code progress-flush-batch} 个对象或
 * {@code progress-flush-interval-millis} 毫秒合并写一次；COMPLETED / FAILED / CANCELLED 终态立即写入。</p>
 */
@Slf4j
@Service
public class StorageMigrationJobService {

    /** 跨实例单飞锁名称：实际 key 为 {root}:lock:storage:migrate:single-flight。 */
    public static final String LOCK_NAME = "storage:migrate:single-flight";

    private final StorageMigrationService migrationService;
    private final MigrationJobStateStore stateStore;
    private final RedisDistributedLock distributedLock;
    private final StorageMigrationJobProperties properties;
    private final RagMetrics metrics;

    /** 本实例持有的任务对象（含已结束任务，用于本实例快速取消/查询）。 */
    private final Map<String, MigrationJob> jobs = new ConcurrentHashMap<>();

    private final ThreadPoolExecutor executor;
    private final ScheduledExecutorService lockRenewer;

    private volatile long lastLockFallbackWarnAt;
    private volatile boolean lockDisabledWarned;

    public StorageMigrationJobService(StorageMigrationService migrationService,
                                      MigrationJobStateStore stateStore,
                                      RedisDistributedLock distributedLock,
                                      StorageMigrationJobProperties properties,
                                      RagMetrics metrics) {
        this.migrationService = migrationService;
        this.stateStore = stateStore;
        this.distributedLock = distributedLock;
        this.properties = properties;
        this.metrics = metrics;
        this.executor = new ThreadPoolExecutor(1, 1, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1),
                runnable -> {
                    Thread thread = new Thread(runnable, "storage-migration-job");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
        this.executor.allowCoreThreadTimeOut(true);
        this.lockRenewer = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "storage-migration-lock-renewer");
            thread.setDaemon(true);
            return thread;
        });
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
        lockRenewer.shutdownNow();
    }

    /**
     * 提交迁移任务并立即返回任务视图；迁移在后台线程池执行。
     * <p><b>全局单飞</b>：启动新迁移必须先拿到 Redis 分布式锁，不支持退回进程内锁。</p>
     * <ul>
     *   <li>锁被其他实例持有 → {@code 已有迁移任务在执行}；</li>
     *   <li>Redis 未启用/命令失败 → {@code 分布式锁不可用，已拒绝启动迁移}，
     *       同时 WARN + {@code wgh.state.store.fallbacks{store=storage-migration-lock}}。</li>
     * </ul>
     */
    public MigrationJobView submit(String sourceType, String targetType, boolean dryRun,
                                   Integer concurrency, Integer maxAttempts) {
        String source = normalizeType(sourceType);
        String target = normalizeType(targetType);
        if (source.equals(target)) {
            throw new BusinessException("源与目标存储类型不能相同: " + source);
        }

        if (!distributedLock.enabled()) {
            rejectLockUnavailable("disabled", null);
            throw new BusinessException("分布式锁不可用，已拒绝启动迁移");
        }
        RedisDistributedLock.LockHandle lockHandle;
        try {
            lockHandle = distributedLock.tryAcquire(LOCK_NAME, properties.lockTtl());
        } catch (RedisUnavailableException e) {
            rejectLockUnavailable("error", e.getMessage());
            throw new BusinessException("分布式锁不可用，已拒绝启动迁移");
        }
        if (lockHandle == null) {
            throw new BusinessException("已有迁移任务在执行");
        }
        recordLockOperation("redis");

        String taskId = UUID.randomUUID().toString();
        try {
            return startJob(taskId, source, target, dryRun, concurrency, maxAttempts, lockHandle);
        } catch (RuntimeException e) {
            jobs.remove(taskId);
            try {
                distributedLock.unlock(lockHandle);
            } catch (RuntimeException unlockError) {
                log.warn("启动失败后释放迁移单飞锁失败（等待 TTL 过期）: {}", unlockError.getMessage());
            }
            if (e instanceof RejectedExecutionException) {
                throw new BusinessException("已有迁移任务在执行");
            }
            throw e;
        }
    }

    private MigrationJobView startJob(String taskId, String source, String target, boolean dryRun,
                                      Integer concurrency, Integer maxAttempts,
                                      RedisDistributedLock.LockHandle lockHandle) {
        MigrationJob job = new MigrationJob(taskId, source, target, dryRun, concurrency, maxAttempts,
                stateStore, properties, lockHandle);
        jobs.put(taskId, job);
        evictOldJobs();
        try {
            job.persistInitial();
            if (lockHandle != null) {
                scheduleRenewal(job);
            }
            executor.execute(() -> run(job));
        } catch (RuntimeException e) {
            job.fail("任务启动失败: " + e.getMessage());
            throw e;
        }
        log.info("存储迁移任务已提交: taskId={}, {} -> {}, dryRun={}", taskId, source, target, dryRun);
        return job.view();
    }

    /**
     * 查询任务实时进度。本实例优先用内存任务对象，其他实例从 Redis 读取；
     * 对运行中任务额外合并 Redis 取消标记，确保其他实例刚写入的取消请求立即可见。
     */
    public MigrationJobView get(String taskId) {
        MigrationJob local = taskId == null ? null : jobs.get(taskId);
        MigrationJobView view = local != null ? local.view() : stateStore.load(taskId);
        if (view == null) {
            throw new BusinessException("迁移任务不存在或已过期（状态 TTL 到期后不可查询）");
        }
        return withRemoteCancel(view);
    }

    /** 按提交时间倒序列出最近的迁移任务，过期任务自动跳过。 */
    public List<MigrationJobView> listRecent(int limit) {
        List<MigrationJobView> views = new ArrayList<>();
        for (String taskId : stateStore.recentTaskIds(limit)) {
            MigrationJob local = jobs.get(taskId);
            MigrationJobView view = local != null ? local.view() : stateStore.load(taskId);
            if (view != null) {
                views.add(withRemoteCancel(view));
            }
        }
        return views;
    }

    /** 运行中任务如果存在 Redis 取消标记，则把 cancelRequested 合并进返回视图。 */
    private MigrationJobView withRemoteCancel(MigrationJobView view) {
        if (view.status() != MigrationJobStatus.RUNNING || view.cancelRequested()) {
            return view;
        }
        return stateStore.isCancelRequested(view.taskId())
                ? view.withCancelRequested(true)
                : view;
    }

    /**
     * 请求取消任务。任意实例都可调用：写 Redis 取消标记，执行实例在
     * {@code cancel-check-interval-millis} 内感知并停止派发后续对象。
     *
     * @return true 表示取消请求已接受；false 表示任务已结束
     */
    public boolean cancel(String taskId) {
        MigrationJob local = taskId == null ? null : jobs.get(taskId);
        if (local != null) {
            if (local.status != MigrationJobStatus.RUNNING) {
                return false;
            }
            local.requestCancel();
            log.info("存储迁移任务收到取消请求: taskId={}, source=local", taskId);
            return true;
        }
        MigrationJobView view = stateStore.load(taskId);
        if (view == null) {
            throw new BusinessException("迁移任务不存在或已过期（状态 TTL 到期后不可查询）");
        }
        if (view.status() != MigrationJobStatus.RUNNING) {
            return false;
        }
        stateStore.requestCancel(taskId);
        log.info("存储迁移任务收到取消请求: taskId={}, source=remote", taskId);
        return true;
    }

    private void run(MigrationJob job) {
        try {
            MigrationReport report = migrationService.migrate(job.sourceType, job.targetType, job.dryRun,
                    job.concurrency, job.maxAttempts, job);
            job.complete(report);
            log.info("存储迁移任务结束: taskId={}, status={}, total={}, copied={}, skipped={}, failed={}",
                    job.id, job.status, report.total(), report.copied(), report.skipped(), report.failed());
        } catch (RuntimeException e) {
            job.fail(e.getMessage());
            log.error("存储迁移任务失败: taskId={}", job.id, e);
        } finally {
            cancelRenewal(job);
            releaseLock(job);
            stateStore.clearCancel(job.id);
            evictOldJobs();
        }
    }

    private void scheduleRenewal(MigrationJob job) {
        long period = Math.max(1L, properties.lockRenewInterval().toSeconds());
        job.renewalFuture = lockRenewer.scheduleAtFixedRate(() -> {
            try {
                if (!distributedLock.renew(job.lockHandle, properties.lockTtl())) {
                    // 锁已丢失（可能过期或被其他实例获取）：按需求不强行终止正在跑的迁移，只告警
                    log.warn("存储迁移单飞锁已丢失，当前任务继续执行，请人工确认是否出现并发迁移: taskId={}",
                            job.id);
                }
            } catch (RuntimeException e) {
                // Redis 中途失效：不中断搬运，进度/终态由状态存储在本地兜底
                log.warn("Redis 不可用，存储迁移单飞锁续约失败；当前任务继续执行，进度将尽量保留在本地: "
                        + "taskId={}, error={}", job.id, e.getMessage());
            }
        }, period, period, TimeUnit.SECONDS);
    }

    private void cancelRenewal(MigrationJob job) {
        if (job.renewalFuture != null) {
            job.renewalFuture.cancel(false);
            job.renewalFuture = null;
        }
    }

    private void releaseLock(MigrationJob job) {
        if (job.lockHandle == null) {
            return;
        }
        try {
            if (!distributedLock.unlock(job.lockHandle)) {
                log.warn("存储迁移单飞锁已不属于当前实例，跳过释放: taskId={}", job.id);
            }
        } catch (RuntimeException e) {
            log.warn("释放存储迁移单飞锁失败（等待 TTL 自动过期）: taskId={}, error={}",
                    job.id, e.getMessage());
        }
    }

    private void evictOldJobs() {
        int limit = Math.max(1, properties.getIndexLimit());
        if (jobs.size() <= limit) {
            return;
        }
        int overflow = jobs.size() - limit;
        List<MigrationJob> finished = jobs.values().stream()
                .filter(job -> job.status != MigrationJobStatus.RUNNING)
                .sorted(Comparator.comparing(job -> job.startedAt))
                .limit(overflow)
                .toList();
        for (MigrationJob job : finished) {
            jobs.remove(job.id);
        }
    }

    private String normalizeType(String type) {
        if (type == null || type.isBlank()) {
            throw new BusinessException("存储类型不能为空");
        }
        String normalized = type.trim().toLowerCase(Locale.ROOT);
        if (!"local".equals(normalized) && !"oss".equals(normalized)) {
            throw new BusinessException("不支持的存储类型: " + type);
        }
        return normalized;
    }

    private void recordLockOperation(String backend) {
        metrics.recordStateStoreOperation("storage-migration", "lock", backend);
    }

    /**
     * 启动路径的锁拒绝：区分 Redis 未启用（disabled）与命令失败（error），
     * 两者都记录 {@code wgh.state.store.fallbacks{store=storage-migration-lock}}，但 WARN 频率不同。
     */
    private void rejectLockUnavailable(String reason, String detail) {
        metrics.recordStateStoreFallback("storage-migration-lock", reason);
        long now = System.currentTimeMillis();
        if ("disabled".equals(reason)) {
            if (!lockDisabledWarned) {
                lockDisabledWarned = true;
                log.warn("分布式锁不可用（Redis 未启用），已拒绝启动存储迁移");
            }
            return;
        }
        if (now - lastLockFallbackWarnAt >= 30_000L) {
            lastLockFallbackWarnAt = now;
            log.warn("分布式锁不可用（Redis 命令失败），已拒绝启动存储迁移: {}", detail);
        }
    }

    /**
     * 单个迁移任务的进程内状态，同时作为迁移引擎的进度监听器。
     */
    private static final class MigrationJob implements MigrationProgressListener {

        private final String id;
        private final String sourceType;
        private final String targetType;
        private final boolean dryRun;
        private final Integer concurrency;
        private final Integer maxAttempts;
        private final MigrationJobStateStore stateStore;
        private final StorageMigrationJobProperties properties;
        private final RedisDistributedLock.LockHandle lockHandle;

        private final AtomicInteger total = new AtomicInteger();
        private volatile boolean totalKnown;
        private final AtomicInteger processed = new AtomicInteger();
        private final AtomicInteger copied = new AtomicInteger();
        private final AtomicInteger skipped = new AtomicInteger();
        private final AtomicInteger planned = new AtomicInteger();
        private final AtomicInteger failed = new AtomicInteger();
        private final AtomicLong copiedBytes = new AtomicLong();

        private final LocalDateTime startedAt = LocalDateTime.now();
        private volatile LocalDateTime finishedAt;
        private volatile MigrationJobStatus status = MigrationJobStatus.RUNNING;
        private volatile String errorMessage;
        private volatile MigrationReport report;
        private volatile boolean cancelRequested;

        private final AtomicInteger pendingSinceFlush = new AtomicInteger();
        private final AtomicLong lastFlushAt = new AtomicLong(System.currentTimeMillis());
        private final AtomicBoolean flushing = new AtomicBoolean(false);
        private final AtomicLong lastCancelCheckAt = new AtomicLong(0L);

        private volatile ScheduledFuture<?> renewalFuture;

        private MigrationJob(String id, String sourceType, String targetType, boolean dryRun,
                             Integer concurrency, Integer maxAttempts,
                             MigrationJobStateStore stateStore,
                             StorageMigrationJobProperties properties,
                             RedisDistributedLock.LockHandle lockHandle) {
            this.id = id;
            this.sourceType = sourceType;
            this.targetType = targetType;
            this.dryRun = dryRun;
            this.concurrency = concurrency;
            this.maxAttempts = maxAttempts;
            this.stateStore = stateStore;
            this.properties = properties;
            this.lockHandle = lockHandle;
        }

        private void persistInitial() {
            save();
            stateStore.addToIndex(id);
        }

        @Override
        public void onTotal(int total) {
            this.total.set(total);
            this.totalKnown = true;
            // 总数只在枚举完成时写一次，让其他实例尽快看到 total/totalKnown
            save();
        }

        @Override
        public void onObjectFinished(StorageObjectInfo object, MigrationOutcome outcome, long bytes) {
            switch (outcome) {
                case COPIED -> copied.incrementAndGet();
                case SKIPPED -> skipped.incrementAndGet();
                case PLANNED -> planned.incrementAndGet();
                case FAILED -> failed.incrementAndGet();
            }
            if (bytes > 0) {
                copiedBytes.addAndGet(bytes);
            }
            processed.incrementAndGet();
            maybePersistProgress();
        }

        @Override
        public boolean isCancelled() {
            if (cancelRequested) {
                return true;
            }
            long now = System.currentTimeMillis();
            long last = lastCancelCheckAt.get();
            long interval = Math.max(1L, properties.getCancelCheckIntervalMillis());
            if (now - last >= interval && lastCancelCheckAt.compareAndSet(last, now)) {
                if (stateStore.isCancelRequested(id)) {
                    cancelRequested = true;
                }
            }
            return cancelRequested;
        }

        private void maybePersistProgress() {
            if (status != MigrationJobStatus.RUNNING) {
                return;
            }
            int pending = pendingSinceFlush.incrementAndGet();
            long now = System.currentTimeMillis();
            int batch = Math.max(1, properties.getProgressFlushBatch());
            long interval = Math.max(1L, properties.getProgressFlushIntervalMillis());
            if (pending < batch && now - lastFlushAt.get() < interval) {
                return;
            }
            if (!flushing.compareAndSet(false, true)) {
                return;
            }
            try {
                pendingSinceFlush.set(0);
                lastFlushAt.set(now);
                save();
            } finally {
                flushing.set(false);
            }
        }

        private void save() {
            try {
                stateStore.save(view());
            } catch (RuntimeException e) {
                log.warn("写入迁移任务状态失败: taskId={}, error={}", id, e.getMessage());
            }
        }

        private void complete(MigrationReport report) {
            this.report = report;
            this.finishedAt = LocalDateTime.now();
            this.status = cancelRequested ? MigrationJobStatus.CANCELLED : MigrationJobStatus.COMPLETED;
            save();
        }

        private void fail(String message) {
            this.errorMessage = message;
            this.finishedAt = LocalDateTime.now();
            this.status = MigrationJobStatus.FAILED;
            save();
        }

        private void requestCancel() {
            cancelRequested = true;
            stateStore.requestCancel(id);
        }

        private MigrationJobView view() {
            return new MigrationJobView(id, status, sourceType, targetType, dryRun, total.get(), totalKnown,
                    processed.get(), copied.get(), skipped.get(), planned.get(), failed.get(),
                    copiedBytes.get(), startedAt, finishedAt, errorMessage, cancelRequested, report);
        }
    }
}
