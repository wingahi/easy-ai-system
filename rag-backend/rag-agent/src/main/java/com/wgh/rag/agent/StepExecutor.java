package com.wgh.rag.agent;

import com.wgh.rag.agent.constant.AgentConstants;
import com.wgh.rag.agent.dto.AgentStepResult;
import com.wgh.rag.agent.dto.AgentStepResult.ModelSwitchRecord;
import com.wgh.rag.agent.dto.AgentStepResult.RagSourceRecord;
import com.wgh.rag.agent.dto.AgentStepResult.ToolInvocationRecord;
import com.wgh.rag.kb.service.RagRetrieveService;
import com.wgh.rag.llm.entity.LlmModel;
import com.wgh.rag.llm.failover.BlockingModelFailover;
import com.wgh.rag.llm.failover.FailoverAttempt;
import com.wgh.rag.llm.failover.FailoverErrors;
import com.wgh.rag.llm.factory.ModelCacheManager;
import com.wgh.rag.llm.service.ModelFailoverService;
import com.wgh.rag.llm.util.RagContextBudgeter;
import com.wgh.rag.llm.util.TokenEstimator;
import com.wgh.rag.tool.entity.ToolConfig;
import com.wgh.rag.tool.executor.ToolExecutionEngine;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.*;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 单步骤执行器 —— 同步 ReAct tool-calling 循环。
 * 与 RagChatService.streamChatWithTools 等价，但返回结构化 AgentStepResult，
 * 不耦合 SseEmitter。依赖 ChatModel（非流式）同步调用。
 *
 * <p>故障转移：整段 ReAct 循环算「一次逻辑调用」，由 rag-llm 的
 * {@link BlockingModelFailover} 驱动。首个模型响应前失败才切链；一旦拿到首个响应
 * （可能已执行工具）后失败不再切换，避免工具副作用被重复执行。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StepExecutor {

    private final ToolExecutionEngine toolExecutionEngine;
    private final ModelCacheManager modelCacheManager;
    private final RagRetrieveService ragRetrieveService;
    private final ModelFailoverService modelFailoverService;

    /**
     * 执行一个 Agent Step —— 同步循环直到 LLM 返回最终文本或达到最大工具轮次。
     * 未显式传入链路时，按执行模型自身的 route_group 解析（与普通 RAG 口径一致）。
     */
    public AgentStepResult execute(
            Long executorModelId,
            String stepPrompt,
            List<ToolSpecification> toolSpecs,
            Map<String, ToolConfig> toolRegistry,
            Long windowModelId,
            int maxToolRounds,
            boolean requiresRAG,
            String ragQuery,
            List<Long> kbIds) {
        return executeWithChain(modelFailoverService.resolveChain(executorModelId),
                stepPrompt, toolSpecs, toolRegistry, windowModelId, maxToolRounds,
                requiresRAG, ragQuery, kbIds);
    }

    /**
     * 执行一个 Agent Step（显式指定故障转移链路，供聊天窗口按窗口 route_group 解析后传入）。
     * 单独命名以避免与 9 参基础重载在 Mockito 匹配时产生重载歧义。
     */
    public AgentStepResult executeWithChain(
            List<LlmModel> chain,
            String stepPrompt,
            List<ToolSpecification> toolSpecs,
            Map<String, ToolConfig> toolRegistry,
            Long windowModelId,
            int maxToolRounds,
            boolean requiresRAG,
            String ragQuery,
            List<Long> kbIds) {

        long startMs = System.currentTimeMillis();

        List<RagSourceRecord> ragSources = new ArrayList<>();
        String promptBuilt = stepPrompt;
        try {
            // 1. 可选 RAG 检索（在进入 tool loop 之前注入，不参与模型故障转移）
            if (requiresRAG && ragQuery != null && !ragQuery.isBlank()
                    && kbIds != null && !kbIds.isEmpty()) {
                List<EmbeddingMatch<TextSegment>> matches = ragRetrieveService.searchMultiple(kbIds, ragQuery);
                // Agent 链路拿不到模型配置，使用默认预算参数（兜底窗口 8192 + 硬上限 6000）
                RagContextBudgeter.Result budgetResult = RagContextBudgeter.trimWithDefaults(
                        matches, TokenEstimator.estimate(stepPrompt) + 32);
                matches = budgetResult.kept();
                log.info("StepExecutor RAG 上下文预算: budget={}, 原始 {} 条/{} tokens, 保留 {} 条/{} tokens, 丢弃 {} 条, 截断={}",
                        budgetResult.budgetTokens(), budgetResult.originalCount(), budgetResult.originalTokens(),
                        budgetResult.keptCount(), budgetResult.keptTokens(), budgetResult.dropped().size(),
                        budgetResult.truncated());
                ragSources.addAll(buildRagSourceRecords(matches));
                String ragContext = extractRagContext(matches);
                promptBuilt = """
                        %s

                        ## 知识库检索结果（仅供参考，事实以检索原文为准）
                        %s
                        """.formatted(stepPrompt, ragContext);
                log.info("StepExecutor RAG 检索到 {} 条", matches.size());
            }
        } catch (Exception e) {
            log.error("StepExecutor RAG 检索异常", e);
            return failureResult(e, null, ragSources, startMs);
        }

        final String enrichedPrompt = promptBuilt;
        final List<RagSourceRecord> finalRagSources = ragSources;
        AttemptHolder lastAttempt = new AttemptHolder();
        List<ModelSwitchRecord> switches = new ArrayList<>();

        try {
            AgentStepResult result = BlockingModelFailover.call(
                    chain,
                    (attempt, llmModel) -> {
                        AttemptScratch scratch = new AttemptScratch();
                        lastAttempt.current = scratch;
                        return executeAttempt(llmModel, attempt, scratch, enrichedPrompt,
                                toolSpecs, toolRegistry, windowModelId, maxToolRounds,
                                finalRagSources, startMs);
                    },
                    new BlockingModelFailover.Listener() {
                        @Override
                        public void onAttemptStart(FailoverAttempt attempt) {
                            if (attempt.index() > 0) {
                                LlmModel model = attempt.model();
                                switches.add(ModelSwitchRecord.builder()
                                        .modelName(model.getModelName())
                                        .modelId(model.getId())
                                        .reason("上一个模型首内容前失败，切换备用模型")
                                        .build());
                            }
                            log.info("StepExecutor 尝试模型 {}/{}: {} (id={})",
                                    attempt.index() + 1, attempt.total(),
                                    attempt.model().getModelName(), attempt.model().getId());
                        }

                        @Override
                        public void onPreContentFailure(FailoverAttempt attempt, Throwable error) {
                            log.warn("StepExecutor 模型 {} 首内容前失败（尝试 {}/{}）：{}",
                                    attempt.model().getModelName(), attempt.index() + 1, attempt.total(),
                                    FailoverErrors.describe(error));
                        }

                        @Override
                        public void onPostContentFailure(FailoverAttempt attempt, Throwable error) {
                            log.error("StepExecutor 模型 {} 已产出内容后失败（尝试 {}/{}）：{}",
                                    attempt.model().getModelName(), attempt.index() + 1, attempt.total(),
                                    FailoverErrors.describe(error));
                        }
                    });
            if (!switches.isEmpty()) {
                result.setModelSwitches(switches);
            }
            return result;
        } catch (Exception e) {
            log.error("StepExecutor 执行异常", e);
            return failureResult(e, lastAttempt.current, ragSources, startMs);
        }
    }

    private AgentStepResult failureResult(Exception e, AttemptScratch scratch,
                                          List<RagSourceRecord> ragSources, long startMs) {
        return AgentStepResult.builder()
                .status(AgentConstants.STATUS_FAILED)
                .finalText("")
                .toolInvocations(scratch != null ? scratch.toolInvocations : new ArrayList<>())
                .ragSources(ragSources)
                .thoughts(scratch != null ? scratch.thoughts : new ArrayList<>())
                .latencyMs(System.currentTimeMillis() - startMs)
                .errorMessage(e.getMessage() != null ? e.getMessage() : "Unknown error")
                .tokenCount(0)
                .build();
    }

    /**
     * 单次模型尝试：完整的同步 ReAct 循环。首个响应即 markStarted，
     * 后续轮次失败不再切链（工具可能已执行）。
     */
    private AgentStepResult executeAttempt(LlmModel llmModel, FailoverAttempt attempt,
                                           AttemptScratch scratch, String enrichedPrompt,
                                           List<ToolSpecification> toolSpecs,
                                           Map<String, ToolConfig> toolRegistry,
                                           Long windowModelId, int maxToolRounds,
                                           List<RagSourceRecord> ragSources, long startMs) {
        // 构建初始 messages —— 把 stepPrompt 作为 UserMessage（Planner 应在 prompt 中包含角色设定）
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(UserMessage.from(enrichedPrompt));

        ChatModel model = modelCacheManager.getChatModel(llmModel.getId());
        int toolRounds = 0;

        // 同步 ReAct tool-calling 循环
        while (true) {
            ChatRequest request = ChatRequest.builder()
                    .messages(new ArrayList<>(messages))
                    .toolSpecifications(toolSpecs != null ? toolSpecs : List.of())
                    .build();

            ChatResponse response = model.chat(request);
            // 已收到模型响应：本次尝试视为已产出内容，后续失败不再切链
            attempt.markStarted();
            AiMessage aiMessage = response.aiMessage();

            List<ToolExecutionRequest> toolCalls = aiMessage.toolExecutionRequests();
            if (toolCalls != null && !toolCalls.isEmpty()) {
                toolRounds++;
                if (toolRounds > maxToolRounds) {
                    log.warn("StepExecutor 工具调用超过最大轮次 {}，截断", maxToolRounds);
                    // 不 break，让最后一个 aiMessage 也入 messages 再走一轮拿最终回答
                }

                log.info("StepExecutor 第 {} 轮工具调用: {} 个", toolRounds, toolCalls.size());

                // 收集 thought：LLM 在发起 tool call 前的文本（如果有）
                String thoughtText = aiMessage.text();
                if (thoughtText != null && !thoughtText.isBlank()) {
                    scratch.thoughts.add(thoughtText);
                }

                // 执行工具调用
                List<ToolExecutionResultMessage> toolResults = new ArrayList<>();
                for (ToolExecutionRequest req : toolCalls) {
                    long tStart = System.currentTimeMillis();
                    ToolExecutionResultMessage result;
                    try {
                        result = toolExecutionEngine.execute(req, toolRegistry, windowModelId);
                    } catch (Exception e) {
                        log.error("工具执行异常: {}", req.name(), e);
                        result = new ToolExecutionResultMessage(
                                req.id(), req.name(), "Error: " + e.getMessage());
                    }
                    long tLatency = System.currentTimeMillis() - tStart;

                    scratch.toolInvocations.add(ToolInvocationRecord.builder()
                            .toolName(req.name())
                            .arguments(req.arguments())
                            .result(result.text())
                            .success(!result.text().startsWith("Error:"))
                            .latencyMs(tLatency)
                            .build());

                    toolResults.add(result);
                }

                // 把 AiMessage + ToolExecutionResultMessage 加入历史
                messages.add(aiMessage);
                messages.addAll(toolResults);

                if (toolRounds > maxToolRounds) {
                    // 让 LLM 基于已有工具结果给出最终回答
                    ChatRequest finalRequest = ChatRequest.builder()
                            .messages(new ArrayList<>(messages))
                            .toolSpecifications(List.of())
                            .build();
                    ChatResponse finalResponse = model.chat(finalRequest);
                    String finalText = finalResponse.aiMessage().text();
                    long latency = System.currentTimeMillis() - startMs;
                    return buildSuccessResult(finalText, scratch, ragSources, latency);
                }
                // 继续循环
            } else {
                // 没有 tool calls —— 最终回答
                String finalText = aiMessage.text();
                long latency = System.currentTimeMillis() - startMs;
                return buildSuccessResult(finalText, scratch, ragSources, latency);
            }
        }
    }

    private AgentStepResult buildSuccessResult(String finalText, AttemptScratch scratch,
                                                List<RagSourceRecord> ragSources, long latencyMs) {
        // resultSummary：取 finalText 前 500 字给下游步骤做上下文注入
        String summary = finalText != null && !finalText.isBlank()
                ? (finalText.length() > 500 ? finalText.substring(0, 500) + "..." : finalText)
                : "";
        return AgentStepResult.builder()
                .status(AgentConstants.STATUS_SUCCESS)
                .finalText(finalText != null ? finalText : "")
                .toolInvocations(scratch.toolInvocations)
                .ragSources(ragSources)
                .thoughts(scratch.thoughts)
                .resultSummary(summary)
                .latencyMs(latencyMs)
                .tokenCount(0) // LangChain4j 1.19.0 ChatResponse 未暴露 token usage，后续可接入 metrics
                .build();
    }

    private List<RagSourceRecord> buildRagSourceRecords(List<EmbeddingMatch<TextSegment>> matches) {
        List<RagSourceRecord> records = new ArrayList<>();
        if (matches == null) return records;
        for (EmbeddingMatch<TextSegment> m : matches) {
            TextSegment seg = m.embedded();
            String fileName = seg != null && seg.metadata() != null ? seg.metadata().getString("fileName") : null;
            Integer chunkIndex = seg != null && seg.metadata() != null ? seg.metadata().getInteger("chunkIndex") : null;
            records.add(RagSourceRecord.builder()
                    .fileName(fileName)
                    .chunkIndex(chunkIndex)
                    .score(m.score())
                    .content(seg != null ? seg.text() : null)
                    .build());
        }
        return records;
    }

    private String extractRagContext(List<EmbeddingMatch<TextSegment>> matches) {
        StringBuilder sb = new StringBuilder();
        if (matches == null) return "";
        for (EmbeddingMatch<TextSegment> match : matches) {
            TextSegment segment = match.embedded();
            if (segment == null) continue;
            String kbName = segment.metadata() != null ? segment.metadata().getString("kbName") : null;
            String fileName = segment.metadata() != null ? segment.metadata().getString("fileName") : null;
            if (kbName != null || fileName != null) {
                sb.append("【来源: ")
                        .append(kbName != null ? kbName : "")
                        .append(" - ")
                        .append(fileName != null ? fileName : "")
                        .append("】\n");
            }
            sb.append(segment.text()).append("\n\n");
        }
        return sb.toString();
    }

    /** 单次尝试的独立产物，重试到下一个模型时不污染上一次的部分结果。 */
    private static final class AttemptScratch {
        private final List<ToolInvocationRecord> toolInvocations = new ArrayList<>();
        private final List<String> thoughts = new ArrayList<>();
    }

    /** 持有最近一次尝试的部分产物，全部失败时用于返回已有工具调用记录。 */
    private static final class AttemptHolder {
        private volatile AttemptScratch current = new AttemptScratch();
    }
}
