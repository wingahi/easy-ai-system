package com.wgh.rag.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 业务指标埋点单测：覆盖正常路径（对话/检索/LLM/工作流成功）与异常路径
 * （对话错误、检索失败、配额超限、限流拒绝）的计数增量。
 */
class RagMetricsTest {

    private SimpleMeterRegistry registry;
    private RagMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new RagMetrics(registry);
    }

    @Test
    @DisplayName("METRIC-01 对话成功路径：请求数 +1、记录耗时与首 token 延迟")
    void chatSuccessPath() {
        Timer.Sample sample = metrics.startSample();
        metrics.recordFirstToken("rag", TimeUnit.MILLISECONDS.toNanos(120));
        metrics.recordChatRequest("rag", false);
        metrics.recordChatDuration(sample, "rag", false);

        assertEquals(1.0, registry.get("wgh.chat.requests")
                .tag("route", "rag").tag("share", "false").counter().count());
        Timer duration = registry.get("wgh.chat.duration")
                .tag("route", "rag").tag("share", "false").timer();
        assertNotNull(duration);
        assertEquals(1, duration.count());
        assertEquals(1, registry.get("wgh.chat.first.token").tag("route", "rag").timer().count());
    }

    @Test
    @DisplayName("METRIC-02 对话异常路径：按 stage 分类累加错误数")
    void chatErrorPath() {
        metrics.recordChatError("retrieve");
        metrics.recordChatError("llm");
        metrics.recordChatError("llm");

        assertEquals(1.0, registry.get("wgh.chat.errors").tag("stage", "retrieve").counter().count());
        assertEquals(2.0, registry.get("wgh.chat.errors").tag("stage", "llm").counter().count());
    }

    @Test
    @DisplayName("METRIC-03 检索正常/异常路径：命中标签区分、异常单独计数")
    void retrievalPaths() {
        metrics.recordRetrieval(TimeUnit.MILLISECONDS.toNanos(30), true, false, true);
        metrics.recordRetrieval(TimeUnit.MILLISECONDS.toNanos(40), false, true, false);
        metrics.recordRetrievalError();

        Counter hit = registry.get("wgh.retrieval.hits")
                .tag("hit", "true").counter();
        Counter miss = registry.get("wgh.retrieval.hits")
                .tag("hit", "false").counter();
        assertEquals(1.0, hit.count());
        assertEquals(1.0, miss.count());
        assertEquals(2, registry.get("wgh.retrieval.duration").timers().stream()
                .mapToLong(Timer::count).sum());
        assertEquals(1.0, registry.get("wgh.retrieval.errors").counter().count());
    }

    @Test
    @DisplayName("METRIC-04 LLM 调用、token 与成本按 provider/type 累加，非正数不记录")
    void llmUsage() {
        metrics.recordLlmCall("DASHSCOPE");
        metrics.recordLlmTokens(100, 50);
        metrics.recordLlmTokens(0, 0);
        metrics.recordLlmCost(0.012);
        metrics.recordLlmCost(-1);

        assertEquals(1.0, registry.get("wgh.llm.calls").tag("provider", "DASHSCOPE").counter().count());
        assertEquals(100.0, registry.get("wgh.llm.tokens").tag("type", "input").counter().count());
        assertEquals(50.0, registry.get("wgh.llm.tokens").tag("type", "output").counter().count());
        assertEquals(0.012, registry.get("wgh.llm.cost").counter().count(), 1e-9);
    }

    @Test
    @DisplayName("METRIC-05 配额超限、限流拒绝、工作流结果与节点失败计数")
    void quotaReadLimitsAndWorkflow() {
        metrics.recordQuotaExceeded("chat");
        metrics.recordQuotaExceeded("token");
        metrics.recordPublicAccess("share-token");
        metrics.recordRateLimitRejected("share-ip");
        metrics.recordWorkflowRun("COMPLETED");
        metrics.recordWorkflowRun("FAILED");
        metrics.recordWorkflowNodeFailure("LLM");

        assertEquals(1.0, registry.get("wgh.quota.exceeded").tag("type", "chat").counter().count());
        assertEquals(1.0, registry.get("wgh.quota.exceeded").tag("type", "token").counter().count());
        assertEquals(1.0, registry.get("wgh.public.access.requests")
                .tag("dimension", "share-token").counter().count());
        assertEquals(1.0, registry.get("wgh.rate.limit.rejected")
                .tag("dimension", "share-ip").counter().count());
        assertEquals(1.0, registry.get("wgh.workflow.runs").tag("status", "completed").counter().count());
        assertEquals(1.0, registry.get("wgh.workflow.runs").tag("status", "failed").counter().count());
        assertEquals(1.0, registry.get("wgh.workflow.node.failures")
                .tag("nodeType", "LLM").counter().count());
    }
}
