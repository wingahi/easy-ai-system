package com.wgh.rag.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 业务指标埋点统一入口。
 * <p>
 * 命名遵循 Micrometer 约定（点号分隔，Prometheus 侧自动转为下划线），标签取值均来自
 * 有限枚举（路由/阶段/维度/节点类型），禁止把 userId、windowId、sessionId 等
 * 高基数标识写入标签，避免 Prometheus 序列爆炸。
 */
@Component
public class RagMetrics {

    /** 对话请求数（route: rag/agent/workflow/cache；share: true/false） */
    public static final String CHAT_REQUESTS = "wgh.chat.requests";
    /** 对话端到端耗时（从请求进入到 SSE 链路结束） */
    public static final String CHAT_DURATION = "wgh.chat.duration";
    /** 首 token 延迟（从请求进入到首个流式片段） */
    public static final String CHAT_FIRST_TOKEN = "wgh.chat.first.token";
    /** 对话错误数（stage: retrieve/llm/stream/agent） */
    public static final String CHAT_ERRORS = "wgh.chat.errors";

    /** 检索耗时（hybrid/rerank 是否启用） */
    public static final String RETRIEVAL_DURATION = "wgh.retrieval.duration";
    /** 检索命中情况（hit: true/false） */
    public static final String RETRIEVAL_HITS = "wgh.retrieval.hits";
    /** 检索异常数 */
    public static final String RETRIEVAL_ERRORS = "wgh.retrieval.errors";
    /** 检索上下文 Token（kind: budget/kept/original） */
    public static final String RAG_CONTEXT_TOKENS = "wgh.rag.context.tokens";
    /** 检索上下文被裁剪情况（kind: chunk/truncated） */
    public static final String RAG_CONTEXT_DROPPED = "wgh.rag.context.dropped";

    /** LLM 调用次数（provider 为有限提供商枚举） */
    public static final String LLM_CALLS = "wgh.llm.calls";
    /** LLM token 用量（type: input/output） */
    public static final String LLM_TOKENS = "wgh.llm.tokens";
    /** LLM 估算成本 */
    public static final String LLM_COST = "wgh.llm.cost";

    /** 配额超限次数（type: chat/token） */
    public static final String QUOTA_EXCEEDED = "wgh.quota.exceeded";

    /** 公开访问请求量（dimension: share-token/share-ip/share-chat/api-key/api-ip 等） */
    public static final String PUBLIC_ACCESS_REQUESTS = "wgh.public.access.requests";
    /** 公开访问限流拒绝次数（dimension 同上） */
    public static final String RATE_LIMIT_REJECTED = "wgh.rate.limit.rejected";

    /** 工作流运行结果（status: completed/failed/paused） */
    public static final String WORKFLOW_RUNS = "wgh.workflow.runs";
    /** 工作流节点失败次数（nodeType: LLM/TOOL/RETRIEVAL/...） */
    public static final String WORKFLOW_NODE_FAILURES = "wgh.workflow.node.failures";

    private final MeterRegistry registry;

    public RagMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public MeterRegistry registry() {
        return registry;
    }

    // ==================== 对话 ====================

    public Timer.Sample startSample() {
        return Timer.start(registry);
    }

    public void recordChatRequest(String route, boolean share) {
        counter(CHAT_REQUESTS, "route", route, "share", String.valueOf(share)).increment();
    }

    public void recordChatDuration(Timer.Sample sample, String route, boolean share) {
        sample.stop(Timer.builder(CHAT_DURATION)
                .tag("route", route)
                .tag("share", String.valueOf(share))
                .publishPercentileHistogram()
                .register(registry));
    }

    public void recordFirstToken(String route, long nanos) {
        Timer.builder(CHAT_FIRST_TOKEN)
                .tag("route", route)
                .publishPercentileHistogram()
                .register(registry)
                .record(nanos, TimeUnit.NANOSECONDS);
    }

    public void recordChatError(String stage) {
        counter(CHAT_ERRORS, "stage", stage).increment();
    }

    // ==================== 检索 ====================

    public void recordRetrieval(long nanos, boolean hybrid, boolean rerank, boolean hit) {
        Timer.builder(RETRIEVAL_DURATION)
                .tag("hybrid", String.valueOf(hybrid))
                .tag("rerank", String.valueOf(rerank))
                .publishPercentileHistogram()
                .register(registry)
                .record(nanos, TimeUnit.NANOSECONDS);
        counter(RETRIEVAL_HITS, "hit", String.valueOf(hit)).increment();
    }

    public void recordRetrievalError() {
        counter(RETRIEVAL_ERRORS).increment();
    }

    /**
     * 检索上下文预算使用情况：预算/原始/保留 Tokens 与丢弃条数。
     * 标签均为有限枚举，不写 windowId/sessionId 等高基数值。
     */
    public void recordRagContextBudget(int budgetTokens, int originalTokens, int keptTokens,
                                       int droppedChunks, boolean truncated) {
        counter(RAG_CONTEXT_TOKENS, "kind", "budget").increment(Math.max(budgetTokens, 0));
        counter(RAG_CONTEXT_TOKENS, "kind", "original").increment(Math.max(originalTokens, 0));
        counter(RAG_CONTEXT_TOKENS, "kind", "kept").increment(Math.max(keptTokens, 0));
        if (droppedChunks > 0) counter(RAG_CONTEXT_DROPPED, "kind", "chunk").increment(droppedChunks);
        if (truncated) counter(RAG_CONTEXT_DROPPED, "kind", "truncated").increment();
    }

    // ==================== LLM ====================

    public void recordLlmCall(String provider) {
        counter(LLM_CALLS, "provider", provider == null ? "unknown" : provider).increment();
    }

    public void recordLlmTokens(long inputTokens, long outputTokens) {
        if (inputTokens > 0) counter(LLM_TOKENS, "type", "input").increment(inputTokens);
        if (outputTokens > 0) counter(LLM_TOKENS, "type", "output").increment(outputTokens);
    }

    public void recordLlmCost(double cost) {
        if (cost <= 0) return;
        counter(LLM_COST).increment(cost);
    }

    // ==================== 配额 / 公开访问 ====================

    public void recordQuotaExceeded(String type) {
        counter(QUOTA_EXCEEDED, "type", type).increment();
    }

    public void recordPublicAccess(String dimension) {
        counter(PUBLIC_ACCESS_REQUESTS, "dimension", dimension == null ? "unknown" : dimension).increment();
    }

    public void recordRateLimitRejected(String dimension) {
        counter(RATE_LIMIT_REJECTED, "dimension", dimension == null ? "unknown" : dimension).increment();
    }

    // ==================== 工作流 ====================

    public void recordWorkflowRun(String status) {
        counter(WORKFLOW_RUNS, "status", status == null ? "unknown" : status.toLowerCase()).increment();
    }

    public void recordWorkflowNodeFailure(String nodeType) {
        counter(WORKFLOW_NODE_FAILURES, "nodeType", nodeType == null ? "unknown" : nodeType).increment();
    }

    private Counter counter(String name, String... tags) {
        return registry.counter(name, tags);
    }
}
