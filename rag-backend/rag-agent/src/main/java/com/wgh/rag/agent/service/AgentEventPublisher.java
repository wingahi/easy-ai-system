package com.wgh.rag.agent.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.function.BiConsumer;

/**
 * SSE 事件统一出口（Observer 模式）。
 * 发送失败仅记警告日志，不触发降级、不影响已落库的业务结果。
 */
@Slf4j
@Component
public class AgentEventPublisher {

    public void emit(BiConsumer<String, Map<String, Object>> sink, String eventName, Map<String, Object> data) {
        if (sink == null) return;
        try {
            sink.accept(eventName, data);
        } catch (Exception e) {
            log.warn("SSE 事件发送失败: {}", eventName, e);
        }
    }
}
