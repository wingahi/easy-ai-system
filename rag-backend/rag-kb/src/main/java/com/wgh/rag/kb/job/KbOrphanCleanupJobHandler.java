package com.wgh.rag.kb.job;

import com.wgh.rag.execution.job.JobContext;
import com.wgh.rag.execution.job.JobHandler;
import com.wgh.rag.execution.job.RecurringJob;
import com.wgh.rag.kb.storage.StorageOrphanCleanupProperties;
import com.wgh.rag.kb.storage.StorageOrphanCleanupScheduler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 孤儿存储对象扫描（{@code KB_ORPHAN_CLEANUP}，维护车道 + 周期作业）。
 *
 * <p>沿用原有 {@link StorageOrphanCleanupScheduler#runOnce()}：其中仍保留 PG advisory lock，
 * 调度中心的维护车道再提供 Redis 单飞，两层都保证多实例同一时刻只跑一份。
 * 本处理器不持有任何状态，配置关闭时直接跳过。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KbOrphanCleanupJobHandler implements JobHandler, RecurringJob {

    private final StorageOrphanCleanupScheduler cleanupScheduler;
    private final StorageOrphanCleanupProperties properties;

    @Override
    public String jobType() {
        return KbJobTypes.ORPHAN_CLEANUP;
    }

    @Override
    public Duration interval() {
        return Duration.ofMinutes(Math.max(5, properties.getIntervalMinutes()));
    }

    @Override
    public void execute(JobContext context) {
        if (!properties.isEnabled()) {
            log.debug("孤儿对象扫描已关闭，跳过本轮");
            return;
        }
        context.cancellation().throwIfCancellationRequested();
        cleanupScheduler.runOnce();
    }
}
