package com.wgh.rag.chat.service.flow;

import com.wgh.rag.chat.constant.ChatEvents;
import com.wgh.rag.chat.service.prompt.ChatContextBudgetService;
import com.wgh.rag.chat.service.prompt.ChatPromptAssembler;
import com.wgh.rag.chat.service.session.ChatMessageStore;
import com.wgh.rag.chat.service.stream.ChatSsePublisher;
import com.wgh.rag.chat.service.window.ChatToolLoader;
import com.wgh.rag.common.metrics.RagMetrics;
import com.wgh.rag.config.property.RagProperties;
import com.wgh.rag.kb.service.RagRetrieveService;
import com.wgh.rag.kb.service.RetrievalOptions;
import com.wgh.rag.kb.service.RetrievalQuality;
import com.wgh.rag.kb.service.RetrieveResult;
import com.wgh.rag.llm.entity.LlmModel;
import com.wgh.rag.llm.service.ModelRouter;
import com.wgh.rag.llm.util.RagContextBudgeter;
import com.wgh.rag.tool.executor.ToolExecutionEngine;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索准备步骤：知识库检索、系统提示与工具装配、上下文预算前置、检索指标下发。
 *
 * <p>预算裁剪后的 chunk 列表同时用于 Prompt、来源卡片与检索指标，保证三者同源。</p>
 */
@Component
@RequiredArgsConstructor
final class RetrievalChatStep implements ChatTurnStep {

    private final RagRetrieveService ragRetrieveService;
    private final ModelRouter modelRouter;
    private final RagProperties ragProperties;
    private final ChatPromptAssembler promptAssembler;
    private final ChatContextBudgetService contextBudgetService;
    private final ChatToolLoader toolLoader;
    private final ToolExecutionEngine toolExecutionEngine;
    private final ChatMessageStore messageStore;
    private final RagMetrics metrics;

    @Override
    public int order() {
        return 300;
    }

    @Override
    public boolean execute(ChatTurnContext ctx) throws Exception {
        RetrieveResult retrieveResult;
        try {
            retrieveResult = ctx.hasKb
                    ? ragRetrieveService.searchMultiple(ctx.kbIds, ctx.userMessage,
                            new RetrievalOptions(resolveRerankModelId(ctx.window),
                                    resolveHybridEnabled(ctx.window),
                                    ctx.window.getQueryTransformMode(),
                                    resolveTransformModelId(ctx.window)))
                    : RetrieveResult.empty();
        } catch (RuntimeException e) {
            metrics.recordChatError("retrieve");
            throw e;
        }
        ctx.retrievedMatches = new ArrayList<>(retrieveResult.matches());

        // 系统提示词（防注入 + 检索式记忆；template: 语法在此解析，userName 取请求线程用户）
        ctx.systemPrompt = promptAssembler.withMemoryBlock(
                promptAssembler.buildSystemPrompt(ctx.window, ctx.quotaUsername),
                ctx.session != null ? ctx.session.getId() : null, ctx.userMessage);

        // 窗口级工具（隔离：只给这个窗口注册它自己的 toolIds 指定的工具）
        ctx.toolSpecs = toolLoader.load(ctx.window);
        // 快照：流式模型的回调在异步线程执行，ThreadLocal 会失效，必须显式传递
        ctx.toolRegistry = toolExecutionEngine.snapshotWindowTools();

        // 上下文预算前置
        boolean willRunLlm = !ctx.retrievedMatches.isEmpty() || !ctx.toolSpecs.isEmpty()
                || !ctx.imageUrls.isEmpty();
        if (!ctx.agentEnabled && willRunLlm) {
            ctx.modelChain = modelRouter.resolveChain(ctx.window.getModelRouteGroup(),
                    ctx.window.getLlmModelId());
            ctx.messages = promptAssembler.buildMessageHistory(ctx.session, ctx.systemPrompt, ctx.userMessage);
        }
        if (!ctx.retrievedMatches.isEmpty()) {
            if (ctx.modelChain == null) {
                ctx.modelChain = modelRouter.resolveChain(ctx.window.getModelRouteGroup(),
                        ctx.window.getLlmModelId());
            }
            // Agent 路线不装配窗口级历史（由步骤内检索负责），按 system + 当前问题估算已占用 prompt
            List<ChatMessage> promptMessages = ctx.messages != null ? ctx.messages
                    : List.of(SystemMessage.from(ctx.systemPrompt), UserMessage.from(ctx.userMessage));
            ctx.contextBudget = contextBudgetService.applyRagBudget(
                    ctx.retrievedMatches, promptMessages, ctx.modelChain.get(0));
        }

        emitRetrievalMetrics(ctx, retrieveResult);
        return false;
    }

    /**
     * 检索指标：coverageRate 是 topK 填充率，hitRate 是分数达标条数 / 返回条数。
     * 未配置知识库时不下发/持久化指标。
     */
    private void emitRetrievalMetrics(ChatTurnContext ctx, RetrieveResult retrieveResult) throws Exception {
        if (!ctx.hasKb) {
            return;
        }
        int topK = ragProperties.getRetrieve().getTopK() != null
                ? ragProperties.getRetrieve().getTopK() : 5;
        double minScore = ragProperties.getRetrieve().getMinScore() != null
                ? ragProperties.getRetrieve().getMinScore() : 0.55;

        Double coverageRate = RetrievalQuality.coverage(ctx.retrievedMatches.size(), topK);
        long highQualityHits = ctx.retrievedMatches.stream()
                .filter(m -> m.score() != null && m.score() >= minScore)
                .count();
        Double hitRate = RetrievalQuality.hitRate((int) highQualityHits, ctx.retrievedMatches.size());
        ctx.hitRate = hitRate;

        Map<String, Object> retrieveInfo = new HashMap<>();
        retrieveInfo.put("coverageRate", coverageRate);
        retrieveInfo.put("hitRate", hitRate);
        retrieveInfo.put("retrievedCount", ctx.contextBudget != null
                ? ctx.contextBudget.originalCount() : ctx.retrievedMatches.size());
        retrieveInfo.put("rerankApplied", retrieveResult.rerankApplied());
        retrieveInfo.put("rerankLatencyMs", retrieveResult.rerankLatencyMs());
        retrieveInfo.put("hybridApplied", retrieveResult.hybridApplied());
        retrieveInfo.put("keywordHitCount", retrieveResult.keywordHitIds().size());
        retrieveInfo.put("transformedQueries", retrieveResult.transformedQueries());
        retrieveInfo.put("transformLatencyMs", retrieveResult.transformLatencyMs());
        retrieveInfo.put("contextKeptCount", ctx.retrievedMatches.size());
        retrieveInfo.put("contextBudgetTokens",
                ctx.contextBudget != null ? ctx.contextBudget.budgetTokens() : null);
        retrieveInfo.put("contextTrimmed", ctx.contextBudget != null
                && (!ctx.contextBudget.dropped().isEmpty() || ctx.contextBudget.truncated()));
        ChatSsePublisher.send(ctx.emitter, ChatEvents.RETRIEVE, retrieveInfo);

        messageStore.updateRetrievalMetrics(ctx.userMsg, coverageRate, hitRate);
    }

    private Long resolveRerankModelId(com.wgh.rag.chat.entity.ChatWindow window) {
        RagProperties.Retrieve retrieve = ragProperties.getRetrieve();
        boolean enabled = Boolean.TRUE.equals(window.getRerankEnabled())
                || Boolean.TRUE.equals(retrieve.getEnableRerank());
        if (!enabled) return null;
        return window.getRerankModelId() != null ? window.getRerankModelId() : retrieve.getRerankModelId();
    }

    private boolean resolveHybridEnabled(com.wgh.rag.chat.entity.ChatWindow window) {
        return Boolean.TRUE.equals(window.getHybridEnabled())
                || Boolean.TRUE.equals(ragProperties.getRetrieve().getEnableHybrid());
    }

    private Long resolveTransformModelId(com.wgh.rag.chat.entity.ChatWindow window) {
        return window.getRewriteModelId() != null ? window.getRewriteModelId() : window.getLlmModelId();
    }
}
