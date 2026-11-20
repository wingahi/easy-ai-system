package com.wgh.rag.execution.model;

import java.time.Instant;
import java.util.Map;

/**
 * 执行状态快照。
 *
 * <p>面向查询与管理端聚合：不暴露具体执行体的业务实体，只提供统一字段 + 自由进度摘要。
 *
 * @param executionId 执行标识
 * @param runId       业务运行记录标识（工作流 run、Agent plan 等），交互式场景可为 {@code null}
 * @param jobType     作业类型
 * @param lane        执行车道
 * @param status      当前状态
 * @param tenantId    租户标识
 * @param ownerUserId 发起人用户标识，系统任务为 {@code null}
 * @param bizKey      业务幂等键，可为 {@code null}
 * @param attempt     已尝试次数，从 1 开始
 * @param progress    进度摘要，结构由执行体定义
 * @param errorCode   失败错误码，非失败状态为 {@code null}
 * @param submittedAt 提交时间
 * @param startedAt   开始执行时间，未开始为 {@code null}
 * @param finishedAt  结束时间，未结束为 {@code null}
 */
public record ExecutionSnapshot(
        String executionId,
        String runId,
        String jobType,
        ExecutionLane lane,
        ExecutionStatus status,
        String tenantId,
        String ownerUserId,
        String bizKey,
        int attempt,
        Map<String, Object> progress,
        String errorCode,
        Instant submittedAt,
        Instant startedAt,
        Instant finishedAt) {

    public ExecutionSnapshot {
        progress = progress == null ? Map.of() : Map.copyOf(progress);
    }
}
