package com.wgh.rag.agent.service;

import com.wgh.rag.common.event.DomainEventPublisher;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.function.BiConsumer;

/**
 * SSE 事件统一出口（Observer 模式）。
 * 发送失败仅记警告日志，不触发降级、不影响已落库的业务结果。
 */
@Component
public class AgentEventPublisher {

    public void emit(BiConsumer<String, Map<String, Object>> sink, String eventName, Map<String, Object> data) {
        DomainEventPublisher.emit(sink, eventName, data);
    }
}
