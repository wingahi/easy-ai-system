package com.wgh.rag.kb.job;

import com.wgh.rag.data.tenant.PlatformScanTemplate;
import com.wgh.rag.execution.job.JobContext;
import com.wgh.rag.execution.job.JobHandler;
import com.wgh.rag.execution.job.RecurringJob;
import com.wgh.rag.kb.service.UploadCleanupProperties;
import com.wgh.rag.kb.service.UploadSessionCleaner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 过期分片上传会话清理（{@code KB_UPLOAD_CLEANUP}，维护车道 + 周期作业）。
 *
 * <p>只删除长时间停留在 {@code UPLOADING} 的会话，COMPLETED 会话与已入库文件不受影响。
 * 维护车道提供跨实例单飞，避免多副本重复扫描同一批会话。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KbUploadCleanupJobHandler implements JobHandler, RecurringJob {

    private final UploadSessionCleaner uploadSessionCleaner;
    private final UploadCleanupProperties properties;
    private final PlatformScanTemplate platformScan;

    @Override
    public String jobType() {
        return KbJobTypes.UPLOAD_CLEANUP;
    }

    @Override
    public Duration interval() {
        return Duration.ofMinutes(Math.max(5, properties.getIntervalMinutes()));
    }

    @Override
    public void execute(JobContext context) {
        if (!properties.isEnabled()) {
            log.debug("分片上传会话清理已关闭，跳过本轮");
            return;
        }
        context.cancellation().throwIfCancellationRequested();
        AtomicLong cleaned = new AtomicLong();
        platformScan.scan(false, () -> context.cancellation().isCancelled(), tenantId -> {
            int count = uploadSessionCleaner.cleanupExpired();
            cleaned.addAndGet(count);
            return new PlatformScanTemplate.TenantCounters(count, 0);
        });
        log.debug("分片上传会话清理完成 executionId={} cleaned={}", context.executionId(), cleaned.get());
    }
}
