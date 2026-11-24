package com.wgh.rag.agentmulti.support;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * SseEventSender 语义钉住测试（已切换到传输无关的 {@link AgentEventStream}）：
 * null 出口跳过、正常发送、下游异常仅标记关闭并 complete、kv 重载容忍 null。
 * 注意：下游实现负责序列化，本测试只验证发送契约与断流语义。
 */
class SseEventSenderTest {

    private final SseEventSender sender = new SseEventSender();

    /** null 出口：静默跳过 */
    @Test
    void send_nullStream_skipsSilently() {
        assertThatCode(() -> sender.send(null, "agent_start", Map.of()))
                .doesNotThrowAnyException();
    }

    /** 正常发送：调用 stream.sendEvent，payload 自动补充 timestamp */
    @Test
    void send_normal_sendsEvent() {
        RecordingStream stream = new RecordingStream();

        sender.send(stream, "agent_start", Map.of("agentId", "a1"));

        assertThat(stream.events).hasSize(1);
        assertThat(stream.events.get(0).name()).isEqualTo("agent_start");
        assertThat(stream.events.get(0).payload()).containsEntry("agentId", "a1").containsKey("timestamp");
    }

    /** 下游异常（客户端断开）：标记关闭 + complete，不向调用方抛出 */
    @Test
    void send_downstreamException_completesWithoutThrowing() {
        RecordingStream stream = new RecordingStream();
        stream.failOnSend = new IllegalStateException("客户端断开");

        assertThatCode(() -> sender.send(stream, "agent_done", Map.of()))
                .doesNotThrowAnyException();
        assertThat(stream.completed.get()).isEqualTo(1);
    }

    /** 断流后不再推送后续事件 */
    @Test
    void send_afterClosed_skipsFurtherEvents() {
        RecordingStream stream = new RecordingStream();
        stream.failOnSend = new IllegalStateException("断开");

        sender.send(stream, "agent_done", Map.of());
        sender.send(stream, "agent_done", Map.of());

        assertThat(stream.events).isEmpty();
        assertThat(stream.completed.get()).isEqualTo(1);
    }

    /** 心跳注释：null 出口跳过 */
    @Test
    void sendComment_nullStream_skipsSilently() {
        assertThatCode(() -> sender.sendComment(null, "hb"))
                .doesNotThrowAnyException();
    }

    /** 心跳注释：下游异常仅标记关闭，不抛出 */
    @Test
    void sendComment_downstreamException_swallowedWithoutThrow() {
        RecordingStream stream = new RecordingStream();
        stream.failOnComment = new IllegalStateException("断开");

        assertThatCode(() -> sender.sendComment(stream, "hb"))
                .doesNotThrowAnyException();
        assertThat(stream.completed.get()).isEqualTo(1);
    }

    /** kv 重载：value 为 null 不抛 NPE（Map.of 遇 null 直接 NPE，曾导致 run 秒败） */
    @Test
    void send_kvOverload_toleratesNullValues() {
        RecordingStream stream = new RecordingStream();

        assertThatCode(() -> sender.send(stream, "agent_start",
                "runId", "r1", "agentName", null, "role", null))
                .doesNotThrowAnyException();
        assertThat(stream.events).hasSize(1);
        assertThat(stream.events.get(0).payload()).containsEntry("runId", "r1").containsEntry("agentName", null);
    }

    /** kv 重载：奇数个参数时末尾孤 key 被忽略（不抛异常、不越界） */
    @Test
    void send_kvOverload_oddArgsIgnoresTrailingKey() {
        RecordingStream stream = new RecordingStream();

        assertThatCode(() -> sender.send(stream, "agent_start", "runId", "r1", "lonelyKey"))
                .doesNotThrowAnyException();
        assertThat(stream.events).hasSize(1);
        assertThat(stream.events.get(0).payload()).containsEntry("runId", "r1").doesNotContainKey("lonelyKey");
    }

    private record SentEvent(String name, Map<String, Object> payload) {
    }

    private static final class RecordingStream implements AgentEventStream {
        private final List<SentEvent> events = new ArrayList<>();
        private final AtomicInteger completed = new AtomicInteger();
        private RuntimeException failOnSend;
        private RuntimeException failOnComment;

        @Override
        public void sendEvent(String eventName, Map<String, Object> payload) {
            if (failOnSend != null) {
                throw failOnSend;
            }
            events.add(new SentEvent(eventName, payload));
        }

        @Override
        public void sendComment(String comment) {
            if (failOnComment != null) {
                throw failOnComment;
            }
        }

        @Override
        public void complete() {
            completed.incrementAndGet();
        }
    }
}
