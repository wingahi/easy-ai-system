package com.wgh.rag.chat.service.flow;

import com.wgh.rag.chat.constant.ChatConstants;
import com.wgh.rag.chat.constant.ChatEvents;
import com.wgh.rag.chat.evaluation.EvaluationScheme;
import com.wgh.rag.chat.evaluation.RetrievalMetricsCalculator;
import com.wgh.rag.chat.service.cost.ChatCostSettler;
import com.wgh.rag.chat.service.evaluation.ChatEvaluationService;
import com.wgh.rag.chat.service.prompt.ChatCitationAssembler;
import com.wgh.rag.chat.service.prompt.ChatContextBudgetService;
import com.wgh.rag.chat.service.session.ChatMessageStore;
import com.wgh.rag.common.metrics.RagMetrics;
import com.wgh.rag.kb.service.SemanticCacheService;
import com.wgh.rag.llm.entity.LlmModel;
import com.wgh.rag.llm.failover.FailoverAttempt;
import com.wgh.rag.llm.failover.FailoverErrors;
import com.wgh.rag.llm.failover.StreamingModelFailover;
import com.wgh.rag.llm.factory.ModelCacheManager;
import com.wgh.rag.tool.fallback.ToolInvocationOutcome;
import com.wgh.rag.tool.fallback.ToolInvoker;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 流式回答执行器：故障转移监听、工具调用循环、最终事件下发与终态落库。
 *
 * <p>故障转移「切不切、切哪个」由 rag-llm 的 {@link StreamingModelFailover} 决定；
 * 本类只实现聊天专属的 SSE、取消、落库、指标与成本语义。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
final class ChatAnswerStreamer {

    private final ModelCacheManager modelCacheManager;
    private final ToolInvoker toolInvoker;
    private final ChatMessageStore messageStore;
    private final ChatCostSettler costSettler;
    private final ChatEvaluationService evaluationService;
    private final ChatContextBudgetService contextBudgetService;
    private final SemanticCacheService semanticCacheService;
    private final RagMetrics metrics;

    /** 按故障转移链路启动第 index 个模型。 */
    void start(ChatTurnContext ctx, int index) {
        failoverOf(ctx).start(index);
    }

    private StreamingModelFailover failoverOf(ChatTurnContext ctx) {
        synchronized (ctx) {
            if (ctx.failover == null) {
                ctx.failover = new StreamingModelFailover(ctx.modelChain, new Listener(ctx),
                        (attempt, model) -> streamChatWithTools(
                                modelCacheManager.getStreamingChatModel(model.getId()),
                                ctx.request, ctx, attempt));
            }
            return ctx.failover;
        }
    }

    /**
     * 带工具调用循环的流式聊天 —— 递归处理 tool calls，直到 LLM 返回最终文本回答。
     */
    private void streamChatWithTools(StreamingChatModel streamingModel, ChatRequest request,
                                     ChatTurnContext ctx, FailoverAttempt attempt) {
        streamingModel.chat(request, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                if (ctx.cancellation.isCancelled()) {
                    log.debug("生成已取消，忽略后续 token: attempt={}/{}", attempt.index() + 1, attempt.total());
                    attempt.deactivate();
                    return;
                }
                if (!attempt.markStarted()) {
                    log.debug("忽略已失效模型尝试的流式片段: attempt={}/{}", attempt.index() + 1, attempt.total());
                    return;
                }
                if (ctx.metricCtx.firstToken().compareAndSet(false, true)) {
                    metrics.recordFirstToken(ctx.metricCtx.route().get(),
                            System.nanoTime() - ctx.metricCtx.startNanos());
                }
                attempt.appendPartial(partialResponse);
                try {
                    // 先落库「已产出」再下发首包：崩溃发生在发送与结算之间时，流水已是 SETTLED，
                    // 补偿作业不会把已交付内容误退（output_at 同步写入，崩溃后仍可见）。
                    if (partialResponse != null && !partialResponse.isBlank()) {
                        ctx.markOutput();
                    }
                    ctx.events.send(ChatEvents.CONTENT, Map.of("text", partialResponse));
                } catch (IOException e) {
                    // 仅总开关关闭（未包装可重放出口）时可能触发：按旧语义取消并落 STOPPED
                    log.warn("发送SSE数据失败，取消生成: {}", e.getMessage());
                    ctx.cancellation.cancel();
                }
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                if (!attempt.isActive()) {
                    log.debug("忽略已失效模型尝试的完成回调: attempt={}/{}", attempt.index() + 1, attempt.total());
                    return;
                }
                if (ctx.cancellation.isCancelled()) {
                    log.info("已取消，跳过完成事件下发: attempt={}/{}", attempt.index() + 1, attempt.total());
                    attempt.deactivate();
                    return;
                }
                attempt.markStarted();
                AiMessage aiMessage = completeResponse.aiMessage();

                TokenUsage usage = completeResponse.tokenUsage();
                ctx.state.addUsage(usage);

                List<ToolExecutionRequest> toolCalls = aiMessage.toolExecutionRequests();
                if (toolCalls != null && !toolCalls.isEmpty()) {
                    int round = ctx.state.nextToolRound();

                    if (round > ctx.maxToolRounds) {
                        log.warn("工具调用超过最大轮次 {}，停止循环", ctx.maxToolRounds);
                        try {
                            sendFinalEvents(ctx, attempt.snapshotContent());
                        } catch (IOException ioe) {
                            log.error("发送最终事件失败", ioe);
                            ctx.cancellation.cancel();
                        }
                        attempt.deactivate();
                        return;
                    }

                    log.info("LLM 请求调用 {} 个工具（第 {} 轮）", toolCalls.size(), round);

                    for (ToolExecutionRequest req : toolCalls) {
                        try {
                            ctx.events.send(ChatEvents.TOOL_CALL_START, Map.of(
                                    "tool", req.name(),
                                    "args", req.arguments()
                            ));
                        } catch (IOException e) {
                            log.warn("发送 tool_call_start 失败，取消生成", e);
                            ctx.cancellation.cancel();
                            attempt.deactivate();
                            return;
                        }
                    }

                    // 执行所有工具调用（显式停止后才终止，客户端断开不再视为取消）
                    List<ToolExecutionResultMessage> toolResults = new ArrayList<>();
                    for (ToolExecutionRequest req : toolCalls) {
                        if (ctx.cancellation.isCancelled()) {
                            log.info("已取消，终止剩余工具调用");
                            attempt.deactivate();
                            return;
                        }
                        ToolInvocationOutcome outcome = toolInvoker.invoke(
                                req, ctx.toolRegistry, ctx.window.getLlmModelId(), ctx.toolBudget);
                        ToolExecutionResultMessage result = outcome.message();
                        toolResults.add(result);

                        try {
                            ctx.events.send(ChatEvents.TOOL_CALL_RESULT, Map.of(
                                    "tool", req.name(),
                                    "success", outcome.success(),
                                    "result", result.text(),
                                    "latencyMs", outcome.latencyMs()
                            ));
                        } catch (IOException e) {
                            log.warn("发送 tool_call_result 失败，取消生成", e);
                            ctx.cancellation.cancel();
                            attempt.deactivate();
                            return;
                        }
                    }

                    if (ctx.cancellation.isCancelled()) {
                        log.info("已取消，不再发起下一轮模型调用");
                        attempt.deactivate();
                        return;
                    }

                    ctx.messages.add(aiMessage);
                    ctx.messages.addAll(toolResults);
                    contextBudgetService.applyToolResultBudget(ctx.messages);

                    ChatRequest nextRequest = ChatRequest.builder()
                            .messages(new ArrayList<>(ctx.messages))
                            .toolSpecifications(ctx.toolSpecs)
                            .build();

                    try {
                        streamChatWithTools(streamingModel, nextRequest, ctx, attempt);
                    } catch (RuntimeException e) {
                        // 下一轮发起的同步异常：按既有语义走故障转移/失败收尾（含计费收口）
                        failoverOf(ctx).handleFailure(attempt, e);
                    }
                    return;
                }

                // 没有 tool calls —— 最终回答，发送完成事件
                try {
                    sendFinalEvents(ctx, attempt.snapshotContent());
                } catch (IOException e) {
                    log.error("完成SSE响应失败", e);
                    ctx.cancellation.cancel();
                }
                attempt.deactivate();
            }

            @Override
            public void onError(Throwable error) {
                if (!attempt.isActive()) {
                    log.debug("忽略已失效模型尝试的错误回调: attempt={}/{}", attempt.index() + 1, attempt.total());
                    return;
                }
                failoverOf(ctx).handleFailure(attempt, error);
            }
        });
    }

    /** 发送最终事件（sources / accuracy / metrics / done / judge）。 */
    private void sendFinalEvents(ChatTurnContext ctx, String fullAnswer) throws IOException {
        // 抢占终态：若已由取消路径落库，则不再下发完成事件、不再重复写入
        if (!ctx.cancellation.markFinalized()) {
            log.info("生成已被取消，跳过完成事件下发与落库");
            return;
        }

        try {
            List<Map<String, Object>> sourceList = ChatCitationAssembler.buildSourcesList(ctx.retrievedMatches);
            ctx.events.send(ChatEvents.SOURCES, sourceList);

            // 正确率同样依赖知识库 —— 未关联知识库时不下发/不持久化
            boolean hasKb = !ctx.kbIds.isEmpty();
            Double accuracyRate = hasKb
                    ? ChatCitationAssembler.calculateAccuracyRate(fullAnswer, ctx.retrievedMatches) : null;
            if (accuracyRate != null) {
                ctx.events.send(ChatEvents.ACCURACY, Map.of("accuracyRate", accuracyRate));
            }

            RetrievalMetricsCalculator.RetrievalMetrics retrievalMetrics = null;
            if (ctx.evaluationScheme.hasMetrics() && hasKb) {
                retrievalMetrics = RetrievalMetricsCalculator.compute(fullAnswer, ctx.retrievedMatches);
                ctx.events.send(ChatEvents.METRICS, Map.of(
                        "mrr", retrievalMetrics.mrr(),
                        "ndcg", retrievalMetrics.ndcg(),
                        "citedCount", retrievalMetrics.citedCount(),
                        "totalRetrieved", retrievalMetrics.totalRetrieved()
                ));
            }

            com.wgh.rag.chat.entity.ChatMessage saved = messageStore.saveAssistantMessage(
                    ctx.session, fullAnswer, sourceList, ctx.hitRate, accuracyRate,
                    retrievalMetrics != null ? retrievalMetrics.mrr() : null,
                    retrievalMetrics != null ? retrievalMetrics.ndcg() : null);

            costSettler.settle(ctx.window, ctx.userMessage, ctx.retrievedMatches, fullAnswer, saved,
                    ctx.state.inputTokens(), ctx.state.outputTokens(),
                    ctx.actualModel.get(), ctx.quotaUsername);

            // 语义缓存：写入问答对（缓存命中路径提前 return，不会走到这里）
            if (Boolean.TRUE.equals(ctx.window.getSemanticCacheEnabled())) {
                semanticCacheService.save(ctx.window.getId(), ctx.kbIds, ctx.userMessage, fullAnswer);
            }

            Map<String, Object> doneData = new HashMap<>();
            doneData.put("fullResponse", fullAnswer);
            if (accuracyRate != null) doneData.put("accuracyRate", accuracyRate);
            if (saved != null && saved.getId() != null) doneData.put("assistantMessageId", saved.getId());
            ctx.events.send(ChatEvents.DONE, doneData);

            if (ctx.evaluationScheme.hasLlmJudge()) {
                evaluationService.runLlmJudgeAsync(ctx.window, ctx.userMessage, ctx.retrievedMatches,
                        fullAnswer, ctx.events, saved);
            } else {
                ctx.events.completeQuietly();
            }
        } finally {
            // 流正常结束：本轮不可能再产出，收口（有产出结算 / 无产出退款）
            ctx.completeTurn();
        }
    }

    /**
     * 用户主动停止的终态落库（explicit 取消；总开关关闭时也用于旧版断连）：保留已生成内容，
     * 状态标记 STOPPED，并按实际产出估算 token 照常计入配额（不回滚为 0）。
     */
    private void persistStopped(ChatTurnContext ctx, FailoverAttempt attempt) {
        attempt.deactivate();
        String partial = attempt.snapshotContent();
        try {
            log.info("生成已停止：保留 {} 字符，session={}", partial.length(),
                    ctx.session != null ? ctx.session.getId() : null);
            com.wgh.rag.chat.entity.ChatMessage saved = messageStore.saveAssistantMessage(
                    ctx.session, partial, List.of(), ctx.hitRate, null, null, null,
                    ChatConstants.MESSAGE_STATUS_STOPPED);
            costSettler.settle(ctx.window, ctx.userMessage, ctx.retrievedMatches, partial, saved,
                    ctx.state.inputTokens(), ctx.state.outputTokens(),
                    ctx.actualModel.get(), ctx.quotaUsername);
            // SSE 连接已断开，不再发送 done / judge 事件
        } finally {
            // 生成终止：本轮不可能再产出，收口
            ctx.completeTurn();
        }
    }

    /**
     * 生成失败（已产出部分内容）的终态落库：状态标记 FAILED，token 按实际产出计数。
     */
    private void persistFailed(ChatTurnContext ctx, FailoverAttempt attempt) {
        String partial = attempt.snapshotContent();
        if (partial.isBlank()) {
            // 无内容可保留，前端仅展示错误提示，不写入空消息
            ctx.cancellation.markFinalized();
            // 异常终止且无产出：本轮不可能再产出，收口（退款）
            ctx.completeTurn();
            return;
        }
        // 抢占终态落库权；若已被取消路径处理则不重复写入
        if (!ctx.cancellation.markFinalized()) {
            return;
        }
        try {
            com.wgh.rag.chat.entity.ChatMessage saved = messageStore.saveAssistantMessage(
                    ctx.session, partial, List.of(), ctx.hitRate, null, null, null,
                    ChatConstants.MESSAGE_STATUS_FAILED);
            costSettler.settle(ctx.window, ctx.userMessage, ctx.retrievedMatches, partial, saved,
                    ctx.state.inputTokens(), ctx.state.outputTokens(),
                    ctx.actualModel.get(), ctx.quotaUsername);
        } finally {
            // 异常终止：本轮不可能再产出，收口（已产出则结算）
            ctx.completeTurn();
        }
    }

    /** 聊天链路故障转移监听器：把切链状态机接到聊天专属的 SSE / 落库 / 指标语义上。 */
    private final class Listener implements StreamingModelFailover.Listener {
        private final ChatTurnContext ctx;

        Listener(ChatTurnContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public void onAttemptCreated(FailoverAttempt attempt) {
            ctx.cancellation.onCancel(() -> persistStopped(ctx, attempt));
            ctx.state.resetToolRounds();
        }

        @Override
        public void onAttemptStart(FailoverAttempt attempt) {
            LlmModel candidate = attempt.model();
            if (attempt.index() > 0) {
                ctx.onModelSwitch.accept(candidate, "上一个模型首token前失败，切换备用模型");
            } else {
                ctx.actualModel.set(candidate);
            }
            log.info("[Failover] 开始尝试 {}/{}：模型 {} (id={})",
                    attempt.index() + 1, attempt.total(), candidate.getModelName(), candidate.getId());
        }

        @Override
        public void onPreContentFailure(FailoverAttempt attempt, Throwable error) {
            metrics.recordChatError("llm");
            log.warn("[Failover] 模型 {} (id={}) 首token前失败（尝试 {}/{}）：{}",
                    attempt.model().getModelName(), attempt.model().getId(),
                    attempt.index() + 1, attempt.total(), FailoverErrors.describe(error));
            ctx.state.resetToolRounds();
        }

        @Override
        public void onPostContentFailure(FailoverAttempt attempt, Throwable error) {
            metrics.recordChatError("llm");
            log.error("LLM流式响应错误（已产出内容，不切换模型）: 模型={}(id={}), 尝试={}/{}, 错误={}",
                    attempt.model().getModelName(), attempt.model().getId(),
                    attempt.index() + 1, attempt.total(), FailoverErrors.describe(error));
            persistFailed(ctx, attempt);
            ctx.events.sendQuietly(ChatEvents.ERROR,
                    Map.of("text", FailoverErrors.userFacing(error)));
            try {
                ctx.events.completeWithError(error);
            } catch (Exception e) {
                log.warn("SSE 关闭失败: {}", e.getMessage());
            }
        }

        @Override
        public void onAllAttemptsFailed(List<LlmModel> chain, Throwable lastError) {
            String message = FailoverErrors.allModelsFailed(chain.size(), lastError);
            log.error("[Failover] {}", message);
            ctx.cancellation.markFinalized();
            ctx.events.sendQuietly(ChatEvents.ERROR, Map.of("text", message));
            ctx.events.completeQuietly();
            // 全部模型失败：本轮不可能再产出，收口（无产出退款）
            ctx.completeTurn();
        }

        @Override
        public boolean isCancelled() {
            return ctx.cancellation.isCancelled();
        }
    }
}
