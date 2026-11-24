package com.wgh.rag.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

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
    /** 分后端检索延迟（backend；kbMode: single/multi） */
    public static final String RETRIEVAL_BACKEND_LATENCY = "wgh.retrieval.backend.latency";
    /** 单次检索 fan-out 的知识库数量 */
    public static final String RETRIEVAL_FANOUT_KB_COUNT = "wgh.retrieval.fanout.kb_count";
    /** 当前向量表（集合）数量 */
    public static final String RETRIEVAL_VECTOR_TABLE_COUNT = "wgh.retrieval.vector.table_count";
    /** 索引构建耗时（backend, collection；单位秒） */
    public static final String RETRIEVAL_INDEX_BUILD = "wgh.retrieval.index.build.seconds";
    /** 关键词路命中情况（backend, hit） */
    public static final String RETRIEVAL_KEYWORD_HIT = "wgh.retrieval.keyword.hit";
    /** 检索降级次数（reason） */
    public static final String RETRIEVAL_DEGRADED = "wgh.retrieval.degraded";
    /** 双读对比执行的查询数（active, compare） */
    public static final String RETRIEVAL_COMPARE_QUERIES = "wgh.retrieval.compare.queries";
    /** 双读对比结果不一致次数（active, compare, kind: top1/set） */
    public static final String RETRIEVAL_COMPARE_MISMATCHES = "wgh.retrieval.compare.mismatches";

    /** LLM 调用次数（provider 为有限提供商枚举） */
    public static final String LLM_CALLS = "wgh.llm.calls";
    /** LLM token 用量（type: input/output） */
    public static final String LLM_TOKENS = "wgh.llm.tokens";
    /** LLM 估算成本 */
    public static final String LLM_COST = "wgh.llm.cost";

    /** 配额超限次数（type: chat/token） */
    public static final String QUOTA_EXCEEDED = "wgh.quota.exceeded";
    /** 统一执行器拒绝后的配额退款结果（result=refunded/duplicate/nothing_to_refund/failed/manual） */
    public static final String QUOTA_REFUND = "wgh.quota.refund";
    /** 配额对话计数异常次数（result=reject 拒绝本次请求 / allow 按 fail-open 放行） */
    public static final String QUOTA_COUNT_FAILURE = "wgh.quota.count.failure";
    /** 管理端人工核销次数（确认扣费有效，不退款） */
    public static final String QUOTA_WRITE_OFF = "wgh.quota.write_off";

    /** 公开访问请求量（dimension: share-token/share-ip/share-chat/api-key/api-ip 等） */
    public static final String PUBLIC_ACCESS_REQUESTS = "wgh.public.access.requests";
    /** 公开访问限流拒绝次数（dimension 同上） */
    public static final String RATE_LIMIT_REJECTED = "wgh.rate.limit.rejected";

    /** 工作流运行结果（status: completed/failed/paused） */
    public static final String WORKFLOW_RUNS = "wgh.workflow.runs";
    /** 工作流节点失败次数（nodeType: LLM/TOOL/RETRIEVAL/...） */
    public static final String WORKFLOW_NODE_FAILURES = "wgh.workflow.node.failures";

    /** 存储迁移对象数（result: copied/skipped/planned/failed） */
    public static final String STORAGE_MIGRATION_OBJECTS = "wgh.storage.migration.objects";
    /** 存储孤儿对象数（action: detected/deleted/delete_failed/skipped_young） */
    public static final String STORAGE_ORPHANS = "wgh.storage.orphans";

    /** 用户信息缓存访问（result: hit/miss/db_load/refill/refill_failed/lock_wait/lock_lost/degraded/evict/evict_failed） */
    public static final String USER_CACHE = "wgh.user.cache";

    /** 共享状态存储降级次数（store: public-access/cancellation；reason: disabled/error） */
    public static final String STATE_STORE_FALLBACKS = "wgh.state.store.fallbacks";
    /** 共享状态存储操作数（store；op: rate-limit/increment/get/set/delete/cancel；backend: redis/memory） */
    public static final String STATE_STORE_OPS = "wgh.state.store.ops";
    /** 对话取消次数（source: client-disconnect/explicit；backend: redis/memory） */
    public static final String CHAT_CANCELLATIONS = "wgh.chat.cancellations";
    /** SSE 事件重放缓冲操作数（kind: chat/workflow-hitl；result: append/detached/gap/incomplete/detach_cancel） */
    public static final String STREAM_REPLAY_EVENTS = "wgh.stream.replay.events";
    /** SSE 事件重放降级次数（kind；reason: disabled/error） */
    public static final String STREAM_REPLAY_FALLBACKS = "wgh.stream.replay.fallbacks";

    /** 长期记忆写入条数（scope: SESSION/TASK） */
    public static final String AGENT_MEMORY_WRITES = "wgh.agent.memory.writes";
    /** 长期记忆去重跳过条数（scope: SESSION/TASK） */
    public static final String AGENT_MEMORY_DEDUP_SKIPS = "wgh.agent.memory.dedup.skips";
    /** 长期记忆召回命中条数（scope: SESSION/TASK） */
    public static final String AGENT_MEMORY_RECALL_HITS = "wgh.agent.memory.recall.hits";
    /** 长期记忆清理删除条数（kind: age/capacity） */
    public static final String AGENT_MEMORY_CLEANUP_DELETED = "wgh.agent.memory.cleanup.deleted";

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

    /** 分后端检索延迟：backend 为有限枚举（pgvector/pgtrgm/...），kbMode 为 single/multi。 */
    public void recordBackendLatency(String backend, String kbMode, long nanos) {
        Timer.builder(RETRIEVAL_BACKEND_LATENCY)
                .tag("backend", safeTag(backend))
                .tag("kbMode", safeTag(kbMode))
                .publishPercentileHistogram()
                .register(registry)
                .record(nanos, TimeUnit.NANOSECONDS);
    }

    /** 单次检索 fan-out 的知识库数量。 */
    public void recordFanoutKbCount(int kbCount) {
        if (kbCount < 0) return;
        DistributionSummary.builder(RETRIEVAL_FANOUT_KB_COUNT)
                .publishPercentileHistogram()
                .register(registry)
                .record(kbCount);
    }

    /**
     * 注册向量表数量 Gauge（由调用方提供带缓存的数量来源，避免每次抓取都查库）。
     * 同名 Gauge 重复注册时 Micrometer 复用同一计量器。
     */
    public void registerVectorTableCount(Supplier<Number> supplier) {
        Gauge.builder(RETRIEVAL_VECTOR_TABLE_COUNT, supplier)
                .register(registry);
    }

    /** 索引构建耗时（单位秒），用于观察 HNSW / 关键词索引的一次性构建成本。 */
    public void recordIndexBuild(String backend, String collection, long nanos) {
        Timer.builder(RETRIEVAL_INDEX_BUILD)
                .tag("backend", safeTag(backend))
                .tag("collection", safeTag(collection))
                .publishPercentileHistogram()
                .register(registry)
                .record(nanos, TimeUnit.NANOSECONDS);
    }

    /** 关键词路命中情况：空结果也记录，便于观察后端召回是否失效。 */
    public void recordKeywordHit(String backend, boolean hit) {
        counter(RETRIEVAL_KEYWORD_HIT,
                "backend", safeTag(backend),
                "hit", String.valueOf(hit)).increment();
    }

    /** 检索降级次数（reason 为有限枚举，如 keyword-fallback / vector-fallback）。 */
    public void recordRetrievalDegraded(String reason) {
        counter(RETRIEVAL_DEGRADED, "reason", safeTag(reason)).increment();
    }

    /** 双读对比：记录查询数与 top1/集合不一致次数，不参与返回结果。 */
    public void recordRetrievalCompare(String activeBackend, String compareBackend,
                                       boolean sameTop1, boolean sameSet) {
        String active = safeTag(activeBackend);
        String compare = safeTag(compareBackend);
        counter(RETRIEVAL_COMPARE_QUERIES, "active", active, "compare", compare).increment();
        if (!sameTop1) {
            counter(RETRIEVAL_COMPARE_MISMATCHES, "active", active, "compare", compare,
                    "kind", "top1").increment();
        }
        if (!sameSet) {
            counter(RETRIEVAL_COMPARE_MISMATCHES, "active", active, "compare", compare,
                    "kind", "set").increment();
        }
    }

    private static String safeTag(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
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

    /**
     * 历史消息 Token 预算裁剪：复用检索上下文指标，kind 追加 history_original / history_kept / history。
     */
    public void recordHistoryBudget(int originalTokens, int keptTokens, int droppedMessages) {
        counter(RAG_CONTEXT_TOKENS, "kind", "history_original").increment(Math.max(originalTokens, 0));
        counter(RAG_CONTEXT_TOKENS, "kind", "history_kept").increment(Math.max(keptTokens, 0));
        if (droppedMessages > 0) counter(RAG_CONTEXT_DROPPED, "kind", "history").increment(droppedMessages);
    }

    /**
     * 工作流 LLM 节点输入预算裁剪：复用检索上下文指标，kind 追加 workflow_prompt_original / workflow_prompt_kept。
     */
    public void recordWorkflowNodePromptTrim(int originalTokens, int keptTokens) {
        counter(RAG_CONTEXT_TOKENS, "kind", "workflow_prompt_original").increment(Math.max(originalTokens, 0));
        counter(RAG_CONTEXT_TOKENS, "kind", "workflow_prompt_kept").increment(Math.max(keptTokens, 0));
        counter(RAG_CONTEXT_DROPPED, "kind", "workflow_prompt").increment(1);
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

    public void recordQuotaRefund(String result) {
        counter(QUOTA_REFUND, "result", result == null ? "unknown" : result).increment();
    }

    public void recordQuotaCountFailure(String result) {
        counter(QUOTA_COUNT_FAILURE, "result", result == null ? "unknown" : result).increment();
    }

    public void recordQuotaWriteOff() {
        counter(QUOTA_WRITE_OFF).increment();
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

    // ==================== 存储运维 ====================

    /** 存储迁移结果计数（result 为有限枚举，避免高基数标签）。 */
    public void recordStorageMigration(String result, long count) {
        if (count > 0) {
            counter(STORAGE_MIGRATION_OBJECTS, "result", result == null ? "unknown" : result).increment(count);
        }
    }

    /** 存储孤儿对象计数（action 为有限枚举）。 */
    public void recordStorageOrphans(String action, long count) {
        if (count > 0) {
            counter(STORAGE_ORPHANS, "action", action == null ? "unknown" : action).increment(count);
        }
    }

    // ==================== 用户信息缓存 ====================

    /**
     * 用户信息缓存访问计数。
     * <p>result 为有限枚举（命中/未命中/回源/回填/锁等待/抢锁失败/降级/失效），
     * 不写 userId，避免 Prometheus 序列爆炸。</p>
     */
    public void recordUserCache(String result) {
        counter(USER_CACHE, "result", result == null ? "unknown" : result).increment();
    }

    // ==================== 多实例共享状态 ====================

    /**
     * 共享状态存储（限流/取消等）降级到进程内实现时计数。
     * <p>仅在状态机切换时上报一次，避免 Redis 故障期间每个请求都 +1 造成尖刺。</p>
     */
    public void recordStateStoreFallback(String store, String reason) {
        counter(STATE_STORE_FALLBACKS,
                "store", store == null ? "unknown" : store,
                "reason", reason == null ? "unknown" : reason).increment();
    }

    /** 共享状态存储操作计数（backend 区分 redis / memory，用于确认多实例是否真正共享） */
    public void recordStateStoreOperation(String store, String op, String backend) {
        counter(STATE_STORE_OPS,
                "store", store == null ? "unknown" : store,
                "op", op == null ? "unknown" : op,
                "backend", backend == null ? "unknown" : backend).increment();
    }

    /** 对话取消计数（backend 区分取消标记是否写入共享存储） */
    public void recordChatCancellation(String source, String backend) {
        counter(CHAT_CANCELLATIONS,
                "source", source == null ? "unknown" : source,
                "backend", backend == null ? "unknown" : backend).increment();
    }

    /**
     * SSE 事件重放缓冲计数。
     * <p>只记录条数（append）与状态事件（detached/gap/incomplete/detach_cancel），
     * 标签取有限枚举，不写 streamId/sessionId。</p>
     */
    public void recordStreamReplayEvent(String kind, String result, long count) {
        if (count <= 0) {
            return;
        }
        counter(STREAM_REPLAY_EVENTS,
                "kind", kind == null ? "unknown" : kind,
                "result", result == null ? "unknown" : result).increment(count);
    }

    /** SSE 事件重放能力降级（reason: disabled=未启用 Redis，error=Redis 故障）。 */
    public void recordStreamReplayFallback(String kind, String reason) {
        counter(STREAM_REPLAY_FALLBACKS,
                "kind", kind == null ? "unknown" : kind,
                "reason", reason == null ? "unknown" : reason).increment();
    }

    // ==================== 长期记忆 ====================

    /** 记忆写入成功（scope 为 SESSION/TASK 有限枚举）。 */
    public void recordMemoryWrite(String scope) {
        counter(AGENT_MEMORY_WRITES, "scope", safeScope(scope)).increment();
    }

    /** 同作用域正文重复导致的写入跳过。 */
    public void recordMemoryDedupSkip(String scope) {
        counter(AGENT_MEMORY_DEDUP_SKIPS, "scope", safeScope(scope)).increment();
    }

    /** 召回命中条数（0 也记录，便于观察召回失败与空命中）。 */
    public void recordMemoryRecallHits(String scope, int hits) {
        counter(AGENT_MEMORY_RECALL_HITS, "scope", safeScope(scope)).increment(Math.max(hits, 0));
    }

    /** 清理删除条数（kind: age/capacity）。 */
    public void recordMemoryCleanupDeleted(String kind, int deleted) {
        if (deleted <= 0) return;
        counter(AGENT_MEMORY_CLEANUP_DELETED, "kind", kind == null ? "unknown" : kind).increment(deleted);
    }

    private static String safeScope(String scope) {
        return scope == null || scope.isBlank() ? "unknown" : scope;
    }

    private Counter counter(String name, String... tags) {
        return registry.counter(name, tags);
    }
}
