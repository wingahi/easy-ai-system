package com.wgh.rag.scheduler.admin;

import com.wgh.rag.execution.model.ExecutionStatus;

/**
 * 管理端取消结果。
 *
 * @param executionId     执行标识
 * @param status          取消受理后的当前状态（RUNNING 作业可能仍处于运行中，等待协作退出）
 * @param cancelRequested 是否已置取消标记
 * @param accepted        取消请求是否被受理
 * @param message         结果说明
 */
public record SchedulerCancelResult(
        String executionId,
        ExecutionStatus status,
        boolean cancelRequested,
        boolean accepted,
        String message) {
}
