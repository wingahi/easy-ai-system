package com.wgh.rag.storage.migration;

import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.metrics.RagMetrics;
import com.wgh.rag.storage.properties.StorageMigrationProperties;
import com.wgh.rag.storage.service.StorageBackend;
import com.wgh.rag.storage.service.StorageBackendFactory;
import com.wgh.rag.storage.service.StorageObjectInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * local ↔ oss 已有对象迁移。
 * <p>
 * 设计要点：
 * <ul>
 *   <li><b>幂等 / 断点续跑</b>：不落迁移进度表；每轮重新扫描源，目标已存在且“大小 + SHA-256”一致的
 *       对象直接跳过。中途失败后重跑即可从未完成对象继续。</li>
 *   <li><b>dry-run</b>：只判断“会搬运 / 已一致”，不写目标存储。</li>
 *   <li><b>并发可控</b>：默认 4，可传参或通过 {@code wgh.rag.storage.migration.concurrency} 覆盖。</li>
 *   <li><b>单对象重试与留痕</b>：每个对象独立重试，失败逐条 WARN/ERROR 日志并汇总进报告。</li>
 *   <li><b>一致性校验</b>：搬运前后都校验大小和 SHA-256，校验不通过按失败处理。</li>
 * </ul>
 * 迁移只搬运对象，不修改数据库；业务读取路径始终由 {@code file_path} 逻辑 key 决定。
 */
@Slf4j
@Service
public class StorageMigrationService {

    private final StorageBackendFactory backendFactory;
    private final StorageMigrationProperties properties;
    private final RagMetrics metrics;

    /** 同一实例内禁止并行迁移，避免重复搬运与目标对象互相覆盖。 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Autowired
    public StorageMigrationService(StorageBackendFactory backendFactory,
                                   StorageMigrationProperties properties,
                                   RagMetrics metrics) {
        this.backendFactory = backendFactory;
        this.properties = properties;
        this.metrics = metrics;
    }

    /** 单测入口：不依赖 Spring 工厂，直接对给定的两个后端执行迁移。 */
    StorageMigrationService(StorageMigrationProperties properties, RagMetrics metrics) {
        this(null, properties, metrics);
    }

    /**
     * 执行迁移。
     *
     * @param sourceType  源存储类型（local / oss）
     * @param targetType  目标存储类型（local / oss），不能与源相同
     * @param dryRun      是否仅演练
     * @param concurrency 并发数，为空取配置默认值
     * @param maxAttempts 单对象最大尝试次数，为空取配置默认值
     */
    public MigrationReport migrate(String sourceType, String targetType, boolean dryRun,
                                   Integer concurrency, Integer maxAttempts) {
        return migrate(sourceType, targetType, dryRun, concurrency, maxAttempts,
                MigrationProgressListener.NOOP);
    }

    /**
     * 执行迁移，并通过 {@code listener} 实时上报进度。
     * <p>
     * 取消是协作式的：监听器的 {@link MigrationProgressListener#isCancelled()} 在每个对象开始处理前检查，
     * 已在处理中的对象会继续完成。
     */
    public MigrationReport migrate(String sourceType, String targetType, boolean dryRun,
                                   Integer concurrency, Integer maxAttempts,
                                   MigrationProgressListener listener) {
        if (backendFactory == null) {
            throw new BusinessException("存储后端工厂未初始化");
        }
        String source = normalizeType(sourceType);
        String target = normalizeType(targetType);
        if (source.equals(target)) {
            throw new BusinessException("源与目标存储类型不能相同: " + source);
        }
        if (!running.compareAndSet(false, true)) {
            throw new BusinessException("已有存储迁移任务正在执行");
        }
        long started = System.currentTimeMillis();
        try (StorageBackend sourceBackend = backendFactory.create(source);
             StorageBackend targetBackend = backendFactory.create(target)) {
            List<StorageObjectInfo> objects = sourceBackend.inventory().listAll();
            return doMigrate(sourceBackend, targetBackend, dryRun, concurrency, maxAttempts,
                    objects, started, listener);
        } finally {
            running.set(false);
        }
    }

    /**
     * 直接对两个已创建的后端执行迁移（单测使用）。
     */
    MigrationReport migrate(StorageBackend source, StorageBackend target, boolean dryRun,
                            Integer concurrency, Integer maxAttempts) {
        return migrate(source, target, dryRun, concurrency, maxAttempts, MigrationProgressListener.NOOP);
    }

    MigrationReport migrate(StorageBackend source, StorageBackend target, boolean dryRun,
                            Integer concurrency, Integer maxAttempts,
                            MigrationProgressListener listener) {
        return doMigrate(source, target, dryRun, concurrency, maxAttempts,
                source.inventory().listAll(), System.currentTimeMillis(), listener);
    }

    private MigrationReport doMigrate(StorageBackend source, StorageBackend target, boolean dryRun,
                                      Integer concurrency, Integer maxAttempts,
                                      List<StorageObjectInfo> objects, long started,
                                      MigrationProgressListener listener) {
        int threads = clamp(concurrency == null ? properties.getConcurrency() : concurrency, 1, 32);
        threads = objects.isEmpty() ? 1 : Math.min(threads, objects.size());
        int attempts = clamp(maxAttempts == null ? properties.getMaxAttempts() : maxAttempts, 1, 10);

        listener.onTotal(objects.size());
        log.info("存储迁移开始: {} -> {}, dryRun={}, objects={}, concurrency={}, maxAttempts={}",
                source.type(), target.type(), dryRun, objects.size(), threads, attempts);

        AtomicInteger copied = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();
        AtomicInteger planned = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        AtomicLong copiedBytes = new AtomicLong();
        ConcurrentLinkedQueue<String> failures = new ConcurrentLinkedQueue<>();

        if (!objects.isEmpty()) {
            ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
                Thread thread = new Thread(r, "storage-migration");
                thread.setDaemon(true);
                return thread;
            });
            try {
                List<Future<?>> futures = new ArrayList<>(objects.size());
                for (StorageObjectInfo object : objects) {
                    futures.add(pool.submit(() -> migrateOne(source, target, object, dryRun, attempts,
                            copied, skipped, planned, failed, copiedBytes, failures, listener)));
                }
                for (Future<?> future : futures) {
                    try {
                        future.get();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new BusinessException("存储迁移被中断");
                    } catch (ExecutionException e) {
                        log.error("存储迁移任务执行异常", e.getCause());
                    }
                }
            } finally {
                pool.shutdownNow();
            }
        }

        long duration = System.currentTimeMillis() - started;
        List<String> failureList = failures.stream()
                .limit(Math.max(1, properties.getMaxFailureEntries()))
                .toList();
        MigrationReport report = new MigrationReport(source.type(), target.type(), dryRun, objects.size(),
                copied.get(), skipped.get(), planned.get(), failed.get(), copiedBytes.get(), duration, failureList);

        metrics.recordStorageMigration("copied", copied.get());
        metrics.recordStorageMigration("skipped", skipped.get());
        metrics.recordStorageMigration("planned", planned.get());
        metrics.recordStorageMigration("failed", failed.get());

        log.info("存储迁移完成: {} -> {}, dryRun={}, total={}, copied={}, skipped={}, planned={}, failed={}, "
                        + "bytes={}, cost={}ms",
                source.type(), target.type(), dryRun, objects.size(), copied.get(), skipped.get(),
                planned.get(), failed.get(), copiedBytes.get(), duration);
        if (listener.isCancelled()) {
            log.warn("存储迁移已取消: {} -> {}, 已处理 {}/{}", source.type(), target.type(),
                    copied.get() + skipped.get() + planned.get() + failed.get(), objects.size());
        }
        if (!failureList.isEmpty()) {
            log.error("存储迁移失败对象 {} 个，样例: {}", failed.get(), failureList);
        }
        return report;
    }

    private void migrateOne(StorageBackend source, StorageBackend target, StorageObjectInfo object,
                            boolean dryRun, int attempts, AtomicInteger copied, AtomicInteger skipped,
                            AtomicInteger planned, AtomicInteger failed, AtomicLong copiedBytes,
                            ConcurrentLinkedQueue<String> failures, MigrationProgressListener listener) {
        String key = object.key();
        if (listener.isCancelled()) {
            log.debug("迁移已请求取消，跳过对象: {}", key);
            return;
        }
        try {
            String sourceHash = null;
            StorageObjectInfo existing = target.inventory().stat(key);
            if (existing != null && existing.size() == object.size()) {
                sourceHash = sha256(source.storage().openStream(key));
                String targetHash = sha256(target.storage().openStream(key));
                if (sourceHash.equals(targetHash)) {
                    skipped.incrementAndGet();
                    listener.onObjectFinished(object, MigrationOutcome.SKIPPED, 0L);
                    log.debug("迁移跳过（目标已存在且大小/哈希一致）: {}", key);
                    return;
                }
                log.info("目标对象内容与源不一致，将重新迁移: key={}, size={}", key, object.size());
            }

            if (dryRun) {
                planned.incrementAndGet();
                listener.onObjectFinished(object, MigrationOutcome.PLANNED, 0L);
                log.info("[dry-run] 待迁移对象: {} ({} bytes)", key, object.size());
                return;
            }

            String expectedHash = sourceHash != null
                    ? sourceHash : sha256(source.storage().openStream(key));
            Exception lastError = null;
            for (int attempt = 1; attempt <= attempts; attempt++) {
                try {
                    copyObject(source, target, key, object.size());
                    verify(key, object.size(), expectedHash, target);
                    copied.incrementAndGet();
                    copiedBytes.addAndGet(object.size());
                    listener.onObjectFinished(object, MigrationOutcome.COPIED, object.size());
                    log.info("迁移对象成功: {} ({} bytes) -> {}", key, object.size(), target.type());
                    return;
                } catch (RuntimeException | IOException e) {
                    lastError = e;
                    log.warn("迁移对象失败（第 {}/{} 次）: key={}, error={}",
                            attempt, attempts, key, e.getMessage());
                    if (attempt < attempts) {
                        sleepQuietly(attempt);
                    }
                }
            }
            failed.incrementAndGet();
            listener.onObjectFinished(object, MigrationOutcome.FAILED, 0L);
            failures.add(key + ": " + messageOf(lastError));
            log.error("迁移对象最终失败: key={}, error={}", key, messageOf(lastError));
        } catch (RuntimeException e) {
            failed.incrementAndGet();
            listener.onObjectFinished(object, MigrationOutcome.FAILED, 0L);
            failures.add(key + ": " + messageOf(e));
            log.error("迁移对象失败: key={}, error={}", key, messageOf(e));
        }
    }

    private void copyObject(StorageBackend source, StorageBackend target, String key, long size)
            throws IOException {
        try (InputStream in = source.storage().openStream(key)) {
            target.storage().store(key, in, size, null);
        }
    }

    private void verify(String key, long expectedSize, String expectedHash, StorageBackend target) {
        StorageObjectInfo stored = target.inventory().stat(key);
        if (stored == null) {
            throw new BusinessException("迁移后目标对象不存在: " + key);
        }
        if (stored.size() != expectedSize) {
            throw new BusinessException("迁移后大小不一致: key=" + key
                    + ", source=" + expectedSize + ", target=" + stored.size());
        }
        String targetHash = sha256(target.storage().openStream(key));
        if (!expectedHash.equals(targetHash)) {
            throw new BusinessException("迁移后哈希不一致: key=" + key);
        }
    }

    private String sha256(InputStream in) {
        try (InputStream stream = in) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) {
            throw new BusinessException("读取存储对象失败: " + e.getMessage(), e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }

    private void sleepQuietly(int attempt) {
        long backoff = Math.min(5000L, Math.max(0L, properties.getRetryBackoffMillis()) * attempt);
        if (backoff <= 0) {
            return;
        }
        try {
            Thread.sleep(backoff);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private String messageOf(Throwable error) {
        if (error == null) {
            return "unknown";
        }
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
