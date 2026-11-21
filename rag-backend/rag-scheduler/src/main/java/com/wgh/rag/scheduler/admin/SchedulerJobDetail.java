package com.wgh.rag.scheduler.admin;

import com.wgh.rag.execution.model.ExecutionLane;
import com.wgh.rag.execution.model.ExecutionStatus;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 管理端作业详情。
 *
 * <p>{@code payload}/{@code result} 为已解析的 JSON 对象；历史负载无法解析时以
 * {@code {"_raw": "..."}} 形式返回原文，避免详情接口因脏数据失败。
 */
public record SchedulerJobDetail(
        String executionId,
        String jobType,
        ExecutionLane lane,
        ExecutionStatus status,
        String tenantId,
        String ownerUserId,
        String bizKey,
        Integer payloadSchemaVersion,
        Integer priority,
        Map<String, Object> payload,
        Map<String, Object> result,
        LocalDateTime scheduledAt,
        LocalDateTime notBefore,
        LocalDateTime nextRetryTime,
        String leaseOwner,
        LocalDateTime leaseExpireTime,
        LocalDateTime heartbeatTime,
        Integer attempt,
        Integer maxAttempts,
        Integer timeoutSeconds,
        Boolean cancelRequested,
        String cancelReason,
        String errorCode,
        String errorMessage,
        LocalDateTime startTime,
        LocalDateTime finishTime,
        Integer version,
        LocalDateTime createTime,
        LocalDateTime updateTime) {
}
