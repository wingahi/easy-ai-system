package com.wgh.rag.tool.fallback;

import com.wgh.rag.tool.entity.ToolConfig;
import com.wgh.rag.tool.executor.ToolExecutionEngine;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工具兜底链单元测试（重试 / 熔断 / 预算 / 错误分类）。
 */
@DisplayName("工具兜底链")
class ToolInvokerTest {

    private ToolExecutionEngine engine;
    private ToolFallbackProperties properties;
    private ToolCircuitBreaker breaker;
    private ToolInvoker invoker;
    private ToolExecutionRequest request;

    @BeforeEach
    void setUp() {
        engine = Mockito.mock(ToolExecutionEngine.class);
        properties = new ToolFallbackProperties();
        properties.setBackoffMs(1);
        properties.setJitterRatio(0);
        breaker = new ToolCircuitBreaker(properties);
        invoker = new ToolInvoker(engine, properties, breaker);
        request = ToolExecutionRequest.builder().id("call-1").name("t").arguments("{}").build();
    }

    private Map<String, ToolConfig> registry(String method) {
        ToolConfig config = new ToolConfig();
        config.setId(10L);
        config.setName("t");
        config.setToolType("HTTP");
        config.setExecutorConfig("{\"method\":\"" + method + "\"}");
        return Map.of("t", config);
    }

    private ToolExecutionResultMessage result(String text) {
        return new ToolExecutionResultMessage("call-1", "t", text);
    }

    @Test
    @DisplayName("网络错误重试后成功")
    void retryThenSuccess() {
        when(engine.execute(any(), any(), any()))
                .thenReturn(result("Error: HttpTimeoutException - timeout"))
                .thenReturn(result("{\"ok\":true}"));

        ToolInvocationOutcome outcome = invoker.invoke(request, registry("GET"), 1L, null);

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.attempts()).isEqualTo(2);
        assertThat(outcome.message().text()).isEqualTo("{\"ok\":true}");
        verify(engine, times(2)).execute(any(), any(), any());
    }

    @Test
    @DisplayName("SSRF 拦截绝不重试")
    void ssrfNeverRetried() {
        when(engine.execute(any(), any(), any()))
                .thenReturn(result("Error: SsrfBlockedException - blocked"));

        ToolInvocationOutcome outcome = invoker.invoke(request, registry("GET"), 1L, null);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.failureKind()).isEqualTo(ToolFailureKind.SSRF);
        assertThat(outcome.attempts()).isEqualTo(1);
        verify(engine, times(1)).execute(any(), any(), any());
    }

    @Test
    @DisplayName("401 鉴权失败不重试")
    void authNeverRetried() {
        when(engine.execute(any(), any(), any())).thenReturn(result("HTTP 401 - unauthorized"));

        ToolInvocationOutcome outcome = invoker.invoke(request, registry("GET"), 1L, null);

        assertThat(outcome.failureKind()).isEqualTo(ToolFailureKind.AUTH);
        assertThat(outcome.attempts()).isEqualTo(1);
        verify(engine, times(1)).execute(any(), any(), any());
    }

    @Test
    @DisplayName("非幂等 POST 不重试")
    void nonIdempotentNotRetried() {
        when(engine.execute(any(), any(), any()))
                .thenReturn(result("Error: HttpTimeoutException - timeout"));

        ToolInvocationOutcome outcome = invoker.invoke(request, registry("POST"), 1L, null);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.attempts()).isEqualTo(1);
        verify(engine, times(1)).execute(any(), any(), any());
    }

    @Test
    @DisplayName("显式 idempotent=true 的 POST 可重试")
    void explicitIdempotentRetried() {
        ToolConfig config = new ToolConfig();
        config.setId(11L);
        config.setToolType("HTTP");
        config.setExecutorConfig("{\"method\":\"POST\",\"idempotent\":true}");
        when(engine.execute(any(), any(), any()))
                .thenReturn(result("HTTP 503 - unavailable"))
                .thenReturn(result("ok"));

        ToolInvocationOutcome outcome = invoker.invoke(request, Map.of("t", config), 1L, null);

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.attempts()).isEqualTo(2);
        verify(engine, times(2)).execute(any(), any(), any());
    }

    @Test
    @DisplayName("熔断打开后直接短路，不再执行工具")
    void circuitOpenShortCircuits() {
        properties.setFailureThreshold(1);
        properties.setMaxAttempts(1);
        when(engine.execute(any(), any(), any()))
                .thenReturn(result("Error: HttpTimeoutException - timeout"));

        ToolInvocationOutcome first = invoker.invoke(request, registry("GET"), 1L, null);
        ToolInvocationOutcome second = invoker.invoke(request, registry("GET"), 1L, null);

        assertThat(first.success()).isFalse();
        assertThat(second.circuitOpen()).isTrue();
        assertThat(second.failureKind()).isEqualTo(ToolFailureKind.CIRCUIT_OPEN);
        assertThat(second.attempts()).isZero();
        assertThat(second.message().text()).startsWith("Error: ");
        verify(engine, times(1)).execute(any(), any(), any());
    }

    @Test
    @DisplayName("任务请求数预算耗尽 - 首次调用即短路")
    void budgetExhaustedBeforeFirstCall() {
        ToolTaskBudget budget = new ToolTaskBudget(0, 1_000);

        ToolInvocationOutcome outcome = invoker.invoke(request, registry("GET"), 1L, budget);

        assertThat(outcome.budgetExhausted()).isTrue();
        assertThat(outcome.failureKind()).isEqualTo(ToolFailureKind.BUDGET_EXHAUSTED);
        assertThat(outcome.attempts()).isZero();
        verify(engine, times(0)).execute(any(), any(), any());
    }

    @Test
    @DisplayName("重试时预算耗尽 - 返回已执行的次数")
    void budgetExhaustedDuringRetry() {
        ToolTaskBudget budget = new ToolTaskBudget(1, 60_000);
        when(engine.execute(any(), any(), any()))
                .thenReturn(result("Error: HttpTimeoutException - timeout"));

        ToolInvocationOutcome outcome = invoker.invoke(request, registry("GET"), 1L, budget);

        assertThat(outcome.budgetExhausted()).isTrue();
        assertThat(outcome.attempts()).isEqualTo(1);
        verify(engine, times(1)).execute(any(), any(), any());
    }

    @Test
    @DisplayName("总开关关闭 - 单次直调不重试")
    void disabledSingleShot() {
        properties.setEnabled(false);
        when(engine.execute(any(), any(), any()))
                .thenReturn(result("Error: HttpTimeoutException - timeout"));

        ToolInvocationOutcome outcome = invoker.invoke(request, registry("GET"), 1L, null);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.attempts()).isEqualTo(1);
        verify(engine, times(1)).execute(any(), any(), any());
    }

    @Test
    @DisplayName("工具执行抛可重试异常 - 包装为 Error 文本并重试")
    void exceptionWrappedAndRetried() {
        when(engine.execute(any(), any(), any()))
                .thenThrow(new RateLimitExceededException("slow"))
                .thenReturn(result("ok"));

        ToolInvocationOutcome outcome = invoker.invoke(request, registry("GET"), 1L, null);

        assertThat(outcome.attempts()).isEqualTo(2);
        assertThat(outcome.message().text()).isEqualTo("ok");
    }

    /** 测试用：简单名包含 RateLimit，触发限流分类（可重试）。 */
    private static final class RateLimitExceededException extends RuntimeException {
        private RateLimitExceededException(String message) {
            super(message);
        }
    }
}
