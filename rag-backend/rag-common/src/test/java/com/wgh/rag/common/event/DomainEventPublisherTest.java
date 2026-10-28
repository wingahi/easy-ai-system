package com.wgh.rag.common.event;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * DomainEventPublisher 单元测试 —— 验证统一出口的三种语义：
 * null sink 跳过、正常推送、异常仅告警吞掉。
 */
class DomainEventPublisherTest {

    /** null sink：直接跳过，不抛异常 */
    @Test
    void emit_nullSink_skips() {
        assertThatCode(() -> DomainEventPublisher.emit(null, "plan_created", Map.of("k", "v")))
                .doesNotThrowAnyException();
    }

    /** 正常推送：sink 收到事件名与原始 payload（同一引用，不做任何加工） */
    @Test
    void emit_deliversEventNameAndPayload() {
        List<String> events = new ArrayList<>();
        List<Map<String, Object>> payloads = new ArrayList<>();
        BiConsumer<String, Map<String, Object>> sink = (e, p) -> {
            events.add(e);
            payloads.add(p);
        };
        Map<String, Object> payload = Map.of("planId", 1L);

        DomainEventPublisher.emit(sink, "plan_created", payload);

        assertThat(events).containsExactly("plan_created");
        assertThat(payloads).containsExactly(payload);
    }

    /** sink 抛异常：仅记警告日志后吞掉，不向外传播 */
    @Test
    void emit_throwingSink_swallowed() {
        AtomicInteger calls = new AtomicInteger();
        BiConsumer<String, Map<String, Object>> sink = (e, p) -> {
            calls.incrementAndGet();
            throw new RuntimeException("boom");
        };

        assertThatCode(() -> DomainEventPublisher.emit(sink, "node_output", Map.of()))
                .doesNotThrowAnyException();
        assertThat(calls).hasValue(1);
    }
}
