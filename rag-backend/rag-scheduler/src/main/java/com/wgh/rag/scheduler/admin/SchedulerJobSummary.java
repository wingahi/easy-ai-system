package com.wgh.rag.scheduler.admin;

import com.wgh.rag.execution.model.ExecutionLane;
import com.wgh.rag.execution.model.ExecutionStatus;

import java.time.LocalDateTime;

/**
 * 管理端作业列表行。
 *
 * <p>只暴露列表展示所需字段，负载与结果等大字段仅在详情接口返回。
 */
public record SchedulerJobSummary(
        String executionId,
        String jobType,
        ExecutionLane lane,
        ExecutionStatus status,
        Integer attempt,
        Integer maxAttempts,
        LocalDateTime scheduledAt,
        LocalDateTime startTime,
        LocalDateTime finishTime,
        String errorCode,
        String tenantId,
        String ownerUserId,
        String bizKey,
        LocalDateTime createTime) {
}
