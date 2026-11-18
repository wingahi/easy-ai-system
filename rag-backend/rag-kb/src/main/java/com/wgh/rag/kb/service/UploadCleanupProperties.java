package com.wgh.rag.kb.service;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 分片上传会话清理配置（代码默认值 + 环境变量，不改 application.yml）。
 * <p>
 * 可用环境变量：{@code WGH_UPLOAD_CLEANUP_ENABLED}、{@code WGH_UPLOAD_CLEANUP_STALE_HOURS}、
 * {@code WGH_UPLOAD_CLEANUP_INTERVAL_MINUTES}、{@code WGH_UPLOAD_CLEANUP_BATCH_SIZE}。
 * <p>
 * 只清理长时间停留在 {@code UPLOADING} 的会话，已 {@code COMPLETED} 的会话与其入库文件不受影响。
 */
@Data
@Component
public class UploadCleanupProperties {

    /** 清理任务开关（默认开启：只删过期未完成会话，风险低）。 */
    @Value("${wgh.upload.cleanup.enabled:true}")
    private boolean enabled = true;

    /** 会话超时阈值（小时）：超过该时长未更新的 UPLOADING 会话会被清理。 */
    @Value("${wgh.upload.cleanup.stale-hours:24}")
    private int staleHours = 24;

    /** 清理轮询间隔（分钟，最小 5）。 */
    @Value("${wgh.upload.cleanup.interval-minutes:60}")
    private int intervalMinutes = 60;

    /** 单轮最多清理的会话数。 */
    @Value("${wgh.upload.cleanup.batch-size:200}")
    private int batchSize = 200;
}
