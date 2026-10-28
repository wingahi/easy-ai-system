package com.wgh.rag.agentmulti.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * SseEventSender 现有语义钉住测试（R7 重构安全网）：
 * null emitter 跳过、正常发送、IOException 仅告警并 completeWithError。
 * 注意：ObjectMapper 必须注册 JavaTimeModule（与 Spring 注入的一致），
 * 否则 payload 的 timestamp（LocalDateTime）序列化失败。
 */
class SseEventSenderTest {

    private final SseEventSender sender =
            new SseEventSender(new ObjectMapper().registerModule(new JavaTimeModule()));

    /** null emitter：静默跳过 */
    @Test
    void send_nullEmitter_skipsSilently() {
        assertThatCode(() -> sender.send(null, "agent_start", Map.of()))
                .doesNotThrowAnyException();
    }

    /** 正常发送：调用 emitter.send，不触发 completeWithError */
    @Test
    void send_normal_sendsEvent() throws IOException {
        SseEmitter emitter = mock(SseEmitter.class);

        sender.send(emitter, "agent_start", Map.of("agentId", "a1"));

        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter, never()).completeWithError(any());
    }

    /** IOException（客户端断开）：仅告警 + completeWithError，不向调用方抛出 */
    @Test
    void send_ioException_completesWithErrorWithoutThrowing() throws IOException {
        SseEmitter emitter = mock(SseEmitter.class);
        IOException boom = new IOException("客户端断开");
        doThrow(boom).when(emitter).send(any(SseEmitter.SseEventBuilder.class));

        assertThatCode(() -> sender.send(emitter, "agent_done", Map.of()))
                .doesNotThrowAnyException();
        verify(emitter).completeWithError(boom);
    }

    /** 心跳注释：null emitter 跳过 */
    @Test
    void sendComment_nullEmitter_skipsSilently() {
        assertThatCode(() -> sender.sendComment(null, "hb"))
                .doesNotThrowAnyException();
    }

    /** 心跳注释：IOException 仅告警，不 completeWithError（与 send 的语义差异钉住） */
    @Test
    void sendComment_ioException_swallowedWithoutComplete() throws IOException {
        SseEmitter emitter = mock(SseEmitter.class);
        doThrow(new IOException("断开")).when(emitter).send(any(SseEmitter.SseEventBuilder.class));

        assertThatCode(() -> sender.sendComment(emitter, "hb"))
                .doesNotThrowAnyException();
        verify(emitter, never()).completeWithError(any());
    }
}
