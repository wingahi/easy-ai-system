package com.wgh.rag.kb.storage;

import com.wgh.rag.storage.migration.MigrationReport;

import java.time.LocalDateTime;

/**
 * 迁移任务的实时视图。
 *
 * @param taskId         任务标识
 * @param status         任务状态
 * @param sourceType     源存储类型
 * @param targetType     目标存储类型
 * @param dryRun         是否演练模式
 * @param total          源对象总数；{@code totalKnown=false} 时表示尚未完成源端枚举
 * @param totalKnown     总数是否已统计
 * @param processed      已处理对象数（copied + skipped + planned + failed）
 * @param copied         已搬运成功数
 * @param skipped        已跳过数（目标已存在且大小/哈希一致）
 * @param planned        dry-run 下待搬运数
 * @param failed         失败数（单对象重试耗尽）
 * @param copiedBytes    已搬运字节数
 * @param startedAt      任务创建（开始）时间
 * @param finishedAt     结束时间；RUNNING 时为 null
 * @param errorMessage   引擎级失败原因；其他状态为 null
 * @param cancelRequested 是否已收到取消请求
 * @param report         完整迁移报告；仅在任务结束后存在
 */
public record MigrationJobView(String taskId, MigrationJobStatus status, String sourceType,
                               String targetType, boolean dryRun, int total, boolean totalKnown,
                               int processed, int copied, int skipped, int planned, int failed,
                               long copiedBytes, LocalDateTime startedAt, LocalDateTime finishedAt,
                               String errorMessage, boolean cancelRequested, MigrationReport report) {

    /** 返回一个仅替换 {@code cancelRequested} 的视图（用于合并其他实例写入的取消标记）。 */
    public MigrationJobView withCancelRequested(boolean requested) {
        return new MigrationJobView(taskId, status, sourceType, targetType, dryRun, total, totalKnown,
                processed, copied, skipped, planned, failed, copiedBytes, startedAt, finishedAt,
                errorMessage, requested, report);
    }
}
