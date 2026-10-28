package com.wgh.rag.agent.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * AgentEventPublisher 现有语义钉住测试（R7 重构安全网）：
 * null sink 跳过、正常发送、发送失败仅告警不抛出。
 */
class AgentEventPublisherTest {

    private final AgentEventPublisher publisher = new AgentEventPublisher();

    /** null sink：静默跳过，不抛异常 */
    @Test
    void emit_nullSink_skipsSilently() {
        assertThatCode(() -> publisher.emit(null, "plan_created", Map.of("planId", 1L)))
                .doesNotThrowAnyException();
    }

    /** 正常发送：事件名与 payload 原样送达 */
    @Test
    void emit_normal_deliversEventAndPayload() {
        List<String> names = new ArrayList<>();
        List<Map<String, Object>> datas = new ArrayList<>();
        BiConsumer<String, Map<String, Object>> sink = (n, d) -> {
            names.add(n);
            datas.add(d);
        };
        Map<String, Object> payload = Map.of("planId", 7L);

        publisher.emit(sink, "step_start", payload);

        assertThat(names).containsExactly("step_start");
        assertThat(datas).containsExactly(payload);
    }

    /** sink 抛异常：仅告警，不向调用方抛出，不影响已落库的业务结果 */
    @Test
    void emit_throwingSink_swallowedWithWarnOnly() {
        BiConsumer<String, Map<String, Object>> flaky = (n, d) -> {
            throw new RuntimeException("客户端已断开");
        };

        assertThatCode(() -> publisher.emit(flaky, "plan_complete", Map.of()))
                .doesNotThrowAnyException();
    }
}
