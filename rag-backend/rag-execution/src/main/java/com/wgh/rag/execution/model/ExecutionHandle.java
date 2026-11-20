package com.wgh.rag.execution.model;

import java.time.Instant;

/**
 * 提交结果句柄。
 *
 * <p>提交是「接受」语义而非「完成」语义：返回句柄时执行体通常尚未开始或仍在进行，
 * 调用方通过 {@link com.wgh.rag.execution.api.ExecutionFacade#query(String)} 或
 * {@link com.wgh.rag.execution.api.ExecutionFacade#attach(String, long, com.wgh.rag.execution.event.ExecutionEventSink)}
 * 获取后续状态。
 *
 * @param executionId 执行标识
 * @param jobType     作业类型
 * @param lane        执行车道
 * @param status      提交后的初始状态
 * @param submittedAt 提交时间
 */
public record ExecutionHandle(
        String executionId,
        String jobType,
        ExecutionLane lane,
        ExecutionStatus status,
        Instant submittedAt) {
}
