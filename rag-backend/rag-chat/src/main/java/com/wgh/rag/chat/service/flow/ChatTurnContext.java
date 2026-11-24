package com.wgh.rag.chat.service.flow;

import com.wgh.rag.chat.entity.ChatSession;
import com.wgh.rag.chat.entity.ChatWindow;
import com.wgh.rag.chat.evaluation.EvaluationScheme;
import com.wgh.rag.chat.service.stream.ChatEventPublisher;
import com.wgh.rag.chat.service.stream.ChatStreamCancellation;
import com.wgh.rag.llm.entity.LlmModel;
import com.wgh.rag.llm.failover.StreamingModelFailover;
import com.wgh.rag.llm.util.RagContextBudgeter;
import com.wgh.rag.tool.entity.ToolConfig;
import com.wgh.rag.tool.fallback.ToolTaskBudget;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.store.embedding.EmbeddingMatch;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

/**
 * 一次对话的请求上下文：请求输入（不可变）+ 各步骤派生的检索/预算状态 + 流式执行状态。
 *
 * <p>替代原先 22 个位置参数的 {@code FailoverContext}，用 Builder 明确必填输入，
 * 派生字段由各步骤就地填充。</p>
 */
public final class ChatTurnContext {

    // ===== 请求输入（不可变） =====
    final ChatWindow window;
    final ChatSession session;
    final String userMessage;
    final ChatEventPublisher events;
    final com.wgh.rag.chat.entity.ChatMessage userMsg;
    final boolean shareMode;
    final String quotaUsername;
    final List<String> imageUrls;
    final ChatMetricContext metricCtx;
    final ChatStreamCancellation cancellation;
    /** 首个有效内容增量下发后的计费回调（可为 null：非计费场景）。 */
    final Runnable outputHook;
    /** 本轮收口回调（有产出结算 / 无产出退款），只在「不可能再产出」时执行一次；可为 null。 */
    final Runnable completionHook;

    // ===== 步骤派生状态 =====
    List<Long> kbIds = List.of();
    boolean hasKb;
    EvaluationScheme evaluationScheme = EvaluationScheme.NONE;
    boolean agentEnabled;
    List<EmbeddingMatch<TextSegment>> retrievedMatches = new ArrayList<>();
    String systemPrompt;
    List<ToolSpecification> toolSpecs = List.of();
    Map<String, ToolConfig> toolRegistry = Map.of();
    List<LlmModel> modelChain;
    List<ChatMessage> messages;
    RagContextBudgeter.Result contextBudget;
    Double hitRate;

    // ===== 流式执行状态 =====
    ChatRequest request;
    int maxToolRounds;
    final ChatTurnState state = new ChatTurnState();
    AtomicReference<LlmModel> actualModel = new AtomicReference<>();
    BiConsumer<LlmModel, String> onModelSwitch;
    ToolTaskBudget toolBudget;
    StreamingModelFailover failover;

    /** 本轮收口已交给异步执行体（流式模型回调 / 工作流运行线程）负责。 */
    private final AtomicBoolean completionDeferred = new AtomicBoolean(false);
    /** 收口是否已执行（幂等闸门）。 */
    private final AtomicBoolean completed = new AtomicBoolean(false);

    private ChatTurnContext(Builder builder) {
        this.window = builder.window;
        this.session = builder.session;
        this.userMessage = builder.userMessage;
        this.events = builder.events;
        this.userMsg = builder.userMsg;
        this.shareMode = builder.shareMode;
        this.quotaUsername = builder.quotaUsername;
        this.imageUrls = builder.imageUrls == null ? List.of() : builder.imageUrls;
        this.metricCtx = builder.metricCtx;
        this.cancellation = builder.cancellation;
        this.outputHook = builder.outputHook;
        this.completionHook = builder.completionHook;
    }

    /**
     * 声明本次对话已向客户端下发有效内容（非空内容增量）。
     * <p>由各答复产出点显式调用；重复调用由回调内部的幂等判断处理。</p>
     */
    public void markOutput() {
        if (outputHook != null) {
            outputHook.run();
        }
    }

    /**
     * 把本轮收口交给异步执行体：编排线程返回后不再立即判定「无产出」，
     * 由异步终态（流正常结束 / 生成终止 / 异常终止）调用 {@link #completeTurn()}。
     *
     * <p>必须在发起异步调用之前声明；若异步调用同步失败，由发起方补一次
     * {@link #completeTurn()}。</p>
     */
    public void deferCompletion() {
        completionDeferred.set(true);
    }

    /**
     * 编排线程收尾：未交给异步执行体时立即收口；已交出则等异步终态收口，
     * 避免在首包到达前提前判定「无产出」并退款。
     */
    public void completeTurnIfNotDeferred() {
        if (!completionDeferred.get()) {
            completeTurn();
        }
    }

    /**
     * 本轮收口（幂等）：只在流正常结束、生成终止、异常终止等「确实不可能再产出」的
     * 收口点调用。有产出则结算，无产出则退款。
     */
    public void completeTurn() {
        if (completed.compareAndSet(false, true) && completionHook != null) {
            completionHook.run();
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private ChatWindow window;
        private ChatSession session;
        private String userMessage;
        private ChatEventPublisher events;
        private com.wgh.rag.chat.entity.ChatMessage userMsg;
        private boolean shareMode;
        private String quotaUsername;
        private List<String> imageUrls = List.of();
        private ChatMetricContext metricCtx;
        private ChatStreamCancellation cancellation;
        private Runnable outputHook;
        private Runnable completionHook;

        public Builder window(ChatWindow window) {
            this.window = window;
            return this;
        }

        public Builder session(ChatSession session) {
            this.session = session;
            return this;
        }

        public Builder userMessage(String userMessage) {
            this.userMessage = userMessage;
            return this;
        }

        public Builder events(ChatEventPublisher events) {
            this.events = events;
            return this;
        }

        public Builder userMsg(com.wgh.rag.chat.entity.ChatMessage userMsg) {
            this.userMsg = userMsg;
            return this;
        }

        public Builder shareMode(boolean shareMode) {
            this.shareMode = shareMode;
            return this;
        }

        public Builder quotaUsername(String quotaUsername) {
            this.quotaUsername = quotaUsername;
            return this;
        }

        public Builder imageUrls(List<String> imageUrls) {
            this.imageUrls = imageUrls;
            return this;
        }

        public Builder metricCtx(ChatMetricContext metricCtx) {
            this.metricCtx = metricCtx;
            return this;
        }

        public Builder cancellation(ChatStreamCancellation cancellation) {
            this.cancellation = cancellation;
            return this;
        }

        public Builder outputHook(Runnable outputHook) {
            this.outputHook = outputHook;
            return this;
        }

        public Builder completionHook(Runnable completionHook) {
            this.completionHook = completionHook;
            return this;
        }

        public ChatTurnContext build() {
            return new ChatTurnContext(this);
        }
    }
}
