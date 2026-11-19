package com.wgh.rag.kb.storage;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 存储迁移异步作业的 Redis / 节流配置（代码默认值，不改 application.yml）。
 * <p>
 * 可用环境变量（Spring relaxed binding）：
 * {@code WGH_RAG_STORAGE_MIGRATION_JOB_TTL_HOURS}、
 * {@code WGH_RAG_STORAGE_MIGRATION_PROGRESS_FLUSH_INTERVAL_MILLIS}、
 * {@code WGH_RAG_STORAGE_MIGRATION_PROGRESS_FLUSH_BATCH}、
 * {@code WGH_RAG_STORAGE_MIGRATION_CANCEL_CHECK_INTERVAL_MILLIS}、
 * {@code WGH_RAG_STORAGE_MIGRATION_INDEX_LIMIT}、
 * {@code WGH_RAG_STORAGE_MIGRATION_LOCK_TTL_SECONDS}。
 */
@Data
@Component
public class StorageMigrationJobProperties {

    /** 任务状态与取消标记的 TTL（小时），默认 24 小时。 */
    @Value("${wgh.rag.storage.migration.job-ttl-hours:24}")
    private long jobTtlHours = 24;

    /** 进度合并写入的间隔（毫秒），默认 1 秒。 */
    @Value("${wgh.rag.storage.migration.progress-flush-interval-millis:1000}")
    private long progressFlushIntervalMillis = 1000;

    /** 进度合并写入的对象数阈值，默认每 50 个对象写一次。 */
    @Value("${wgh.rag.storage.migration.progress-flush-batch:50}")
    private int progressFlushBatch = 50;

    /** 执行实例检查取消标记的间隔（毫秒），默认 1 秒。 */
    @Value("${wgh.rag.storage.migration.cancel-check-interval-millis:1000}")
    private long cancelCheckIntervalMillis = 1000;

    /** 任务索引保留条数上限，默认 50。 */
    @Value("${wgh.rag.storage.migration.index-limit:50}")
    private int indexLimit = 50;

    /** 跨实例单飞锁 TTL（秒），默认 120 秒。 */
    @Value("${wgh.rag.storage.migration.lock-ttl-seconds:120}")
    private long lockTtlSeconds = 120;

    /** 锁续约间隔（秒）；0 表示按 TTL/3 自动计算，默认 0。 */
    @Value("${wgh.rag.storage.migration.lock-renew-interval-seconds:0}")
    private long lockRenewIntervalSeconds = 0;

    /** 状态 key 的 TTL。 */
    public Duration jobTtl() {
        return Duration.ofHours(Math.max(1, jobTtlHours));
    }

    /** 单飞锁 TTL，至少 30 秒。 */
    public Duration lockTtl() {
        return Duration.ofSeconds(Math.max(30, lockTtlSeconds));
    }

    /** 锁续约间隔：显式配置优先，否则取 TTL/3（至少 5 秒）。 */
    public Duration lockRenewInterval() {
        if (lockRenewIntervalSeconds > 0) {
            return Duration.ofSeconds(lockRenewIntervalSeconds);
        }
        return Duration.ofSeconds(Math.max(5, lockTtl().toSeconds() / 3));
    }
}
