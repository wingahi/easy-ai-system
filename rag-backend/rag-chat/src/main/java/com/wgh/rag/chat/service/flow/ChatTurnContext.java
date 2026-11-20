package com.wgh.rag.chat.service.flow;

import com.wgh.rag.chat.entity.ChatSession;
import com.wgh.rag.chat.entity.ChatWindow;
import com.wgh.rag.chat.evaluation.EvaluationScheme;
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    final SseEmitter emitter;
    final com.wgh.rag.chat.entity.ChatMessage userMsg;
    final boolean shareMode;
    final String quotaUsername;
    final List<String> imageUrls;
    final ChatMetricContext metricCtx;
    final ChatStreamCancellation cancellation;

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

    private ChatTurnContext(Builder builder) {
        this.window = builder.window;
        this.session = builder.session;
        this.userMessage = builder.userMessage;
        this.emitter = builder.emitter;
        this.userMsg = builder.userMsg;
        this.shareMode = builder.shareMode;
        this.quotaUsername = builder.quotaUsername;
        this.imageUrls = builder.imageUrls == null ? List.of() : builder.imageUrls;
        this.metricCtx = builder.metricCtx;
        this.cancellation = builder.cancellation;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private ChatWindow window;
        private ChatSession session;
        private String userMessage;
        private SseEmitter emitter;
        private com.wgh.rag.chat.entity.ChatMessage userMsg;
        private boolean shareMode;
        private String quotaUsername;
        private List<String> imageUrls = List.of();
        private ChatMetricContext metricCtx;
        private ChatStreamCancellation cancellation;

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

        public Builder emitter(SseEmitter emitter) {
            this.emitter = emitter;
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

        public ChatTurnContext build() {
            return new ChatTurnContext(this);
        }
    }
}
