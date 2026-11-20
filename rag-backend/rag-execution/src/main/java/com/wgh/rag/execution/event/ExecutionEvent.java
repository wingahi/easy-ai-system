package com.wgh.rag.execution.event;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * 执行事件。
 *
 * <p>{@code seq} 在同一执行内单调递增，用于 SSE 重连时的断点续传（S3 阶段接事件表）。
 * {@code type} 取值见 {@link ExecutionEventTypes}；{@code payload} 结构由执行体定义。
 *
 * @param executionId 执行标识
 * @param seq         执行内单调递增序号，从 1 开始
 * @param type        事件类型
 * @param payload     事件负载
 * @param occurredAt  发生时间
 */
public record ExecutionEvent(
        String executionId,
        long seq,
        String type,
        Map<String, Object> payload,
        Instant occurredAt) {

    public ExecutionEvent {
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(type, "type");
        if (seq <= 0) {
            throw new IllegalArgumentException("seq 必须从 1 开始");
        }
        payload = payload == null ? Map.of() : Map.copyOf(payload);
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
    }
}
