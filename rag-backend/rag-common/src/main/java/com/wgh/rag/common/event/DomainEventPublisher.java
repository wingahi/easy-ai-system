package com.wgh.rag.common.event;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.function.BiConsumer;

/**
 * 领域事件统一出口（Observer 模式）—— 各模块 SSE 事件推送共用的发送核心。
 * <p>
 * 语义与 rag-agent 的 AgentEventPublisher 保持一致：
 * sink 为 null 时跳过；发送失败仅记警告日志，不抛出异常，
 * 不触发降级、不影响已落库的业务结果。
 * <p>
 * 注意：本类只统一「发送失败仅告警」这一类出口。工作流引擎等调用点
 * 既有「发送失败影响流程」语义的，不属于本类的适用范围。
 */
@Slf4j
public final class DomainEventPublisher {

    private DomainEventPublisher() {
    }

    /**
     * 向 sink 推送一个事件。sink 为 null 时直接跳过；
     * sink 抛出的任何异常仅记警告日志后吞掉。
     */
    public static void emit(BiConsumer<String, Map<String, Object>> sink, String eventName, Map<String, Object> data) {
        if (sink == null) return;
        try {
            sink.accept(eventName, data);
        } catch (Exception e) {
            log.warn("SSE 事件发送失败: {}", eventName, e);
        }
    }
}
