package com.wgh.rag.agent;

import com.wgh.rag.agent.dto.AgentStepResult;
import com.wgh.rag.agent.dto.AgentStepResult.RagSourceRecord;
import com.wgh.rag.agent.dto.AgentStepResult.ToolInvocationRecord;
import com.wgh.rag.kb.service.RagRetrieveService;
import com.wgh.rag.llm.factory.ModelCacheManager;
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
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StepExecutor {

    private final ToolExecutionEngine toolExecutionEngine;
    private final ModelCacheManager modelCacheManager;
    private final RagRetrieveService ragRetrieveService;

    /**
     * 执行一个 Agent Step —— 同步循环直到 LLM 返回最终文本或达到最大工具轮次。
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

        long startMs = System.currentTimeMillis();

        List<ToolInvocationRecord> toolInvocations = new ArrayList<>();
        List<RagSourceRecord> ragSources = new ArrayList<>();
        List<String> thoughts = new ArrayList<>();

        try {
            // 1. 可选 RAG 检索（在进入 tool loop 之前注入）
            String enrichedPrompt = stepPrompt;
            if (requiresRAG && ragQuery != null && !ragQuery.isBlank()
                    && kbIds != null && !kbIds.isEmpty()) {
                List<EmbeddingMatch<TextSegment>> matches = ragRetrieveService.searchMultiple(kbIds, ragQuery);
                ragSources = buildRagSourceRecords(matches);
                String ragContext = extractRagContext(matches);
                enrichedPrompt = """
                        %s

                        ## 知识库检索结果（仅供参考，事实以检索原文为准）
                        %s
                        """.formatted(stepPrompt, ragContext);
                log.info("StepExecutor RAG 检索到 {} 条", matches.size());
            }

            // 2. 构建初始 messages —— 把 stepPrompt 作为 UserMessage（Planner 应在 prompt 中包含角色设定）
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(UserMessage.from(enrichedPrompt));

            ChatModel model = modelCacheManager.getChatModel(executorModelId);
            int toolRounds = 0;

            // 3. 同步 ReAct tool-calling 循环
            while (true) {
                ChatRequest request = ChatRequest.builder()
                        .messages(new ArrayList<>(messages))
                        .toolSpecifications(toolSpecs != null ? toolSpecs : List.of())
                        .build();

                ChatResponse response = model.chat(request);
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
                        thoughts.add(thoughtText);
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

                        toolInvocations.add(ToolInvocationRecord.builder()
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
                        return buildSuccessResult(finalText, toolInvocations, ragSources, thoughts, latency);
                    }
                    // 继续循环
                } else {
                    // 4. 没有 tool calls —— 最终回答
                    String finalText = aiMessage.text();
                    long latency = System.currentTimeMillis() - startMs;
                    return buildSuccessResult(finalText, toolInvocations, ragSources, thoughts, latency);
                }
            }

        } catch (Exception e) {
            log.error("StepExecutor 执行异常", e);
            long latency = System.currentTimeMillis() - startMs;
            return AgentStepResult.builder()
                    .status("FAILED")
                    .finalText("")
                    .toolInvocations(toolInvocations)
                    .ragSources(ragSources)
                    .thoughts(thoughts)
                    .latencyMs(latency)
                    .errorMessage(e.getMessage() != null ? e.getMessage() : "Unknown error")
                    .tokenCount(0)
                    .build();
        }
    }

    private AgentStepResult buildSuccessResult(String finalText,
                                                List<ToolInvocationRecord> toolInvocations,
                                                List<RagSourceRecord> ragSources,
                                                List<String> thoughts,
                                                long latencyMs) {
        // resultSummary：取 finalText 前 500 字给下游步骤做上下文注入
        String summary = finalText != null && !finalText.isBlank()
                ? (finalText.length() > 500 ? finalText.substring(0, 500) + "..." : finalText)
                : "";
        return AgentStepResult.builder()
                .status("SUCCESS")
                .finalText(finalText != null ? finalText : "")
                .toolInvocations(toolInvocations)
                .ragSources(ragSources)
                .thoughts(thoughts)
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
}
