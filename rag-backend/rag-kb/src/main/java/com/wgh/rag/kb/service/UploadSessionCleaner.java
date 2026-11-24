package com.wgh.rag.kb.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wgh.rag.kb.entity.UploadSession;
import com.wgh.rag.kb.mapper.UploadSessionMapper;
import com.wgh.rag.storage.service.FileStorageService;
import com.wgh.rag.storage.service.StorageKeys;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 过期分片上传会话清理。
 * <p>
 * 只清理状态为 {@code UPLOADING} 且超过阈值未更新的会话，连同其分片对象一起删除；
 * {@code COMPLETED} 会话与其入库文件不在清理范围内。默认每小时执行一次，可通过
 * {@code wgh.upload.cleanup.*}（或对应环境变量）调整；{@link #cleanupExpired()} 也可手动触发。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UploadSessionCleaner {

    private static final String STATUS_UPLOADING = "UPLOADING";

    private final UploadSessionMapper sessionMapper;
    private final FileStorageService fileStorageService;
    private final UploadCleanupProperties properties;

    /** 调度中心开启时由 {@code KB_UPLOAD_CLEANUP} 周期作业接管，本地定时器不启动。 */
    @Value("${wgh.scheduler.enabled:true}")
    private boolean schedulerEnabled = true;

    private ThreadPoolTaskScheduler scheduler;

    @PostConstruct
    void start() {
        if (!properties.isEnabled()) {
            log.info("分片上传会话清理未启用（wgh.upload.cleanup.enabled=false）");
            return;
        }
        if (schedulerEnabled) {
            log.info("分片上传会话清理已交由调度中心（KB_UPLOAD_CLEANUP），本地定时器不启动");
            return;
        }
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("upload-session-cleaner-");
        scheduler.initialize();
        scheduler.scheduleWithFixedDelay(this::cleanupQuietly,
                Instant.now().plus(Duration.ofMinutes(Math.max(5, properties.getIntervalMinutes()))),
                Duration.ofMinutes(Math.max(5, properties.getIntervalMinutes())));
        log.info("分片上传会话清理已启用：超时 {} 小时，间隔 {} 分钟",
                Math.max(1, properties.getStaleHours()), Math.max(5, properties.getIntervalMinutes()));
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    /**
     * 清理超时未完成的会话，返回清理数量。任何单个会话失败都不影响其他会话。
     */
    @Transactional
    public int cleanupExpired() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(Math.max(1, properties.getStaleHours()));
        List<UploadSession> stale = sessionMapper.selectList(new LambdaQueryWrapper<UploadSession>()
                .eq(UploadSession::getStatus, STATUS_UPLOADING)
                .lt(UploadSession::getUpdateTime, cutoff)
                .last("LIMIT " + Math.max(1, properties.getBatchSize())));

        int cleaned = 0;
        for (UploadSession session : stale) {
            try {
                deleteChunks(session);
                sessionMapper.deleteById(session.getId());
                cleaned++;
            } catch (RuntimeException e) {
                log.warn("过期上传会话清理失败: sessionId={}, error={}", session.getId(), e.getMessage());
            }
        }
        if (cleaned > 0) {
            log.info("过期分片上传会话已清理 {} 个（阈值 {} 小时前）", cleaned, Math.max(1, properties.getStaleHours()));
        }
        return cleaned;
    }

    private void deleteChunks(UploadSession session) {
        int total = session.getTotalChunks() == null ? 0 : session.getTotalChunks();
        for (int i = 0; i < total; i++) {
            String key = StorageKeys.chunkKey(session.getKnowledgeBaseId(), session.getUploadUuid(), i);
            try {
                fileStorageService.delete(key);
            } catch (RuntimeException e) {
                log.warn("过期分片对象删除失败: key={}, error={}", key, e.getMessage());
            }
        }
    }

    private void cleanupQuietly() {
        try {
            cleanupExpired();
        } catch (RuntimeException e) {
            log.warn("过期上传会话清理任务异常: {}", e.getMessage());
        }
    }
}
