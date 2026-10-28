package com.wgh.rag.agentmulti.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SSE 统一事件发送工具 —— 所有多 Agent 事件走此出口，统一 JSON 序列化 + 错误处理。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SseEventSender {

    private final ObjectMapper objectMapper;

    /** 发送一个命名事件。payload 会被包裹上 timestamp 字段后序列化为 JSON。 */
    public void send(SseEmitter emitter, String eventName, Object payload) {
        if (emitter == null) return;
        try {
            Map<String, Object> finalPayload;
            if (payload instanceof Map<?, ?> m) {
                finalPayload = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    finalPayload.put(String.valueOf(e.getKey()), e.getValue());
                }
            } else {
                finalPayload = new LinkedHashMap<>();
                finalPayload.put("data", payload);
            }
            finalPayload.put("timestamp", LocalDateTime.now());

            emitter.send(SseEmitter.event()
                    .name(eventName)
                    .data(objectMapper.writeValueAsString(finalPayload)));
        } catch (IOException e) {
            log.warn("SSE 事件发送失败 event={}", eventName, e);
            // 客户端断开 —— 标记完成以便调用方尽快结束
            emitter.completeWithError(e);
        }
    }

    /** 发送注释（保持连接心跳用） */
    public void sendComment(SseEmitter emitter, String comment) {
        if (emitter == null) return;
        try {
            emitter.send(SseEmitter.event().comment(comment));
        } catch (IOException e) {
            log.warn("SSE 心跳发送失败", e);
        }
    }
}
