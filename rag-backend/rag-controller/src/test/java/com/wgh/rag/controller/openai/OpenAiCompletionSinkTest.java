package com.wgh.rag.controller.publicaccess.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.chat.constant.ChatEvents;
import com.wgh.rag.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 校验 OpenAI 分块结构、增量顺序、usage 与错误帧。
 */
class OpenAiCompletionSinkTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldEmitIncrementalChunksThenFinishAndDone() throws Exception {
        OpenAiCompletionSink sink = new OpenAiCompletionSink(objectMapper, "chatcmpl-1", 123L,
                "wgh-window-1", 9L, "你好", true, true);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        sink.startStreaming(output);
        sink.event(ChatEvents.CONTENT, Map.of("text", "你"));
        sink.event(ChatEvents.CONTENT, Map.of("text", "好"));
        sink.event(ChatEvents.DONE, Map.of("fullResponse", "你好"));
        sink.await(1, TimeUnit.SECONDS);

        String stream = output.toString(StandardCharsets.UTF_8);
        assertThat(stream)
                .contains("\"object\":\"chat.completion.chunk\"")
                .contains("\"model\":\"wgh-window-1\"")
                .contains("\"wgh_session_id\":9")
                .contains("\"delta\":{\"role\":\"assistant\",\"content\":\"\"}")
                .contains("\"delta\":{\"content\":\"你\"}")
                .contains("\"delta\":{\"content\":\"好\"}")
                .contains("\"finish_reason\":\"stop\"")
                .contains("\"usage\"")
                .contains("\"total_tokens\"")
                .contains("data: [DONE]");
        assertThat(stream.indexOf("\"content\":\"你\""))
                .isLessThan(stream.indexOf("\"content\":\"好\""));
        assertThat(sink.contentText()).isEqualTo("你好");
    }

    @Test
    void shouldNotEmitUsageChunkWhenNotRequested() {
        OpenAiCompletionSink sink = new OpenAiCompletionSink(objectMapper, "chatcmpl-2", 123L,
                "wgh-window-1", null, "hi", true, false);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        sink.startStreaming(output);
        sink.event(ChatEvents.CONTENT, Map.of("text", "ok"));
        sink.event(ChatEvents.DONE, Map.of("fullResponse", "ok"));

        String stream = output.toString(StandardCharsets.UTF_8);
        assertThat(stream).doesNotContain("\"usage\"");
        assertThat(stream).contains("data: [DONE]");
    }

    @Test
    void shouldEmitOpenAiErrorFrameInStreamingMode() {
        OpenAiCompletionSink sink = new OpenAiCompletionSink(objectMapper, "chatcmpl-3", 123L,
                "wgh-window-1", null, "hi", true, false);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        sink.startStreaming(output);
        sink.event(ChatEvents.ERROR, Map.of("text", "模型调用失败"));

        String stream = output.toString(StandardCharsets.UTF_8);
        assertThat(stream)
                .contains("\"error\"")
                .contains("\"message\":\"模型调用失败\"")
                .contains("\"type\":\"api_error\"")
                .contains("data: [DONE]");
        // 流式模式下错误在帧内返回，等待不抛出
        sink.await(1, TimeUnit.SECONDS);
    }

    @Test
    void shouldSurfaceFailureForNonStreamingMode() {
        OpenAiCompletionSink sink = new OpenAiCompletionSink(objectMapper, "chatcmpl-4", 123L,
                "wgh-window-1", null, "hi", false, false);

        sink.event(ChatEvents.ERROR, Map.of("text", "上游异常"));

        assertThatThrownBy(() -> sink.await(1, TimeUnit.SECONDS))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("上游异常");
    }

    @Test
    void shouldFinishWhenEmitterCompletesWithoutDoneEvent() {
        OpenAiCompletionSink sink = new OpenAiCompletionSink(objectMapper, "chatcmpl-5", 123L,
                "wgh-window-1", null, "hi", true, false);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        sink.startStreaming(output);
        sink.event(ChatEvents.CONTENT, Map.of("text", "部分"));
        sink.completed();

        String stream = output.toString(StandardCharsets.UTF_8);
        assertThat(stream).contains("\"finish_reason\":\"stop\"").contains("data: [DONE]");
    }
}
