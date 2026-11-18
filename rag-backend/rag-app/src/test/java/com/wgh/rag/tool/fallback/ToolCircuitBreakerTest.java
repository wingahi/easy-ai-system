package com.wgh.rag.tool.fallback;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具熔断器状态机测试。
 */
@DisplayName("工具熔断器")
class ToolCircuitBreakerTest {

    private final AtomicLong now = new AtomicLong(1_000L);
    private final ToolFallbackProperties properties = new ToolFallbackProperties();

    private ToolCircuitBreaker newBreaker() {
        return new ToolCircuitBreaker(properties, now::get);
    }

    @Test
    @DisplayName("连续失败达阈值 - 打开并短路")
    void opensAfterThreshold() {
        ToolCircuitBreaker breaker = newBreaker();
        for (int i = 0; i < 5; i++) {
            assertThat(breaker.tryAcquire("tool")).isTrue();
            breaker.onFailure("tool");
        }
        assertThat(breaker.isOpen("tool")).isTrue();
        assertThat(breaker.tryAcquire("tool")).isFalse();
    }

    @Test
    @DisplayName("打开到期 - 只放行一个半开探针")
    void halfOpenAllowsSingleProbe() {
        ToolCircuitBreaker breaker = newBreaker();
        for (int i = 0; i < 5; i++) {
            breaker.onFailure("tool");
        }
        assertThat(breaker.tryAcquire("tool")).isFalse();

        now.addAndGet(properties.getOpenSeconds() * 1000L);
        assertThat(breaker.tryAcquire("tool")).isTrue();
        // 探针占用中，其他调用继续短路
        assertThat(breaker.tryAcquire("tool")).isFalse();

        breaker.onSuccess("tool");
        assertThat(breaker.tryAcquire("tool")).isTrue();
        assertThat(breaker.isOpen("tool")).isFalse();
    }

    @Test
    @DisplayName("半开探针失败 - 重新计时打开")
    void failedProbeReopens() {
        ToolCircuitBreaker breaker = newBreaker();
        for (int i = 0; i < 5; i++) {
            breaker.onFailure("tool");
        }
        now.addAndGet(properties.getOpenSeconds() * 1000L);
        assertThat(breaker.tryAcquire("tool")).isTrue();

        breaker.onFailure("tool");
        assertThat(breaker.tryAcquire("tool")).isFalse();

        now.addAndGet(properties.getOpenSeconds() * 1000L);
        assertThat(breaker.tryAcquire("tool")).isTrue();
    }

    @Test
    @DisplayName("成功调用 - 清零失败计数")
    void successResetsFailures() {
        ToolCircuitBreaker breaker = newBreaker();
        for (int i = 0; i < 4; i++) {
            breaker.onFailure("tool");
        }
        breaker.onSuccess("tool");
        for (int i = 0; i < 4; i++) {
            breaker.onFailure("tool");
        }
        assertThat(breaker.isOpen("tool")).isFalse();
        assertThat(breaker.tryAcquire("tool")).isTrue();
    }

    @Test
    @DisplayName("不同工具互不影响")
    void isolatesTools() {
        ToolCircuitBreaker breaker = newBreaker();
        for (int i = 0; i < 5; i++) {
            breaker.onFailure("tool-a");
        }
        assertThat(breaker.tryAcquire("tool-a")).isFalse();
        assertThat(breaker.tryAcquire("tool-b")).isTrue();
    }
}
