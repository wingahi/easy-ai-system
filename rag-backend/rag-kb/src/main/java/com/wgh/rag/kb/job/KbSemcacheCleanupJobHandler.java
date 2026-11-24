package com.wgh.rag.kb.job;

import com.wgh.rag.config.property.RagProperties;
import com.wgh.rag.execution.job.JobContext;
import com.wgh.rag.execution.job.JobHandler;
import com.wgh.rag.execution.job.RecurringJob;
import com.wgh.rag.kb.service.SemanticCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 语义缓存过期清理（{@code KB_SEMCACHE_CLEANUP}，维护车道 + 周期作业）。
 *
 * <p>沿用 {@link SemanticCacheService#cleanupExpired()} 的单批清理能力，按批循环直到清完或达到轮次上限，
 * 避免高频写入下每轮只删 200 条而长期追不上。维护车道提供跨实例单飞，多副本只跑一份。
 *
 * <p>语义缓存全局关闭时直接跳过；查询侧已按 TTL 过滤，清理只影响存储占用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KbSemcacheCleanupJobHandler implements JobHandler, RecurringJob {

    /** 单轮最多清理批次数，避免一次维护作业占用车道过久。 */
    private static final int MAX_BATCHES_PER_RUN = 10;

    private final SemanticCacheService semanticCacheService;

    private final RagProperties ragProperties;

    @Override
    public String jobType() {
        return KbJobTypes.SEMCACHE_CLEANUP;
    }

    @Override
    public Duration interval() {
        Integer minutes = ragProperties.getCache().getCleanupIntervalMinutes();
        return Duration.ofMinutes(minutes == null ? 30 : Math.max(5, minutes));
    }

    @Override
    public void execute(JobContext context) {
        if (Boolean.FALSE.equals(ragProperties.getCache().getEnabled())) {
            log.debug("语义缓存已关闭，跳过过期清理");
            return;
        }
        int batchSize = ragProperties.getCache().getCleanupBatchSize() == null
                ? 200 : Math.max(1, ragProperties.getCache().getCleanupBatchSize());
        int total = 0;
        for (int round = 0; round < MAX_BATCHES_PER_RUN; round++) {
            context.cancellation().throwIfCancellationRequested();
            int cleaned = semanticCacheService.cleanupExpired();
            total += cleaned;
            if (cleaned < batchSize) {
                break;
            }
        }
        log.debug("语义缓存过期清理完成 executionId={} cleaned={}", context.executionId(), total);
    }
}
