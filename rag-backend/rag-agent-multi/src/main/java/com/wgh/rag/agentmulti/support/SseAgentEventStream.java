package com.wgh.rag.agentmulti.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * HTTP SSE 事件出口：把结构化负载序列化为 JSON 写入 emitter。
 */
@RequiredArgsConstructor
public class SseAgentEventStream implements AgentEventStream {

    private final SseEmitter emitter;

    private final ObjectMapper objectMapper;

    @Override
    public void sendEvent(String eventName, Map<String, Object> payload) {
        try {
            emitter.send(SseEmitter.event()
                    .name(eventName)
                    .data(objectMapper.writeValueAsString(payload)));
        } catch (Exception e) {
            throw new IllegalStateException("SSE 写出失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void sendComment(String comment) {
        try {
            emitter.send(SseEmitter.event().comment(comment));
        } catch (Exception e) {
            throw new IllegalStateException("SSE 注释写出失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void complete() {
        try {
            emitter.complete();
        } catch (Exception ignored) {
            // 下游已关闭
        }
    }
}
