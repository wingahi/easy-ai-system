package com.wgh.rag.agentmulti.support;

import com.wgh.rag.agentmulti.config.MultiAgentContextProperties;
import com.wgh.rag.agentmulti.constant.MultiAgentEvents;
import com.wgh.rag.agentmulti.context.MultiAgentContext;
import com.wgh.rag.agentmulti.dto.AgentInstance;
import com.wgh.rag.agentmulti.dto.AgentInstanceResult;
import com.wgh.rag.agentmulti.dto.AgentResultCard;
import com.wgh.rag.agent.support.AgentTaskMemory;
import com.wgh.rag.kb.service.RagRetrieveService;
import com.wgh.rag.config.property.RagProperties;
import com.wgh.rag.llm.entity.LlmModel;
import com.wgh.rag.llm.factory.ModelCacheManager;
import com.wgh.rag.llm.failover.BlockingModelFailover;
import com.wgh.rag.llm.failover.FailoverAttempt;
import com.wgh.rag.llm.service.ModelFailoverService;
import com.wgh.rag.llm.util.RagContextBudgeter;
import com.wgh.rag.llm.util.TokenEstimator;
import com.wgh.rag.llm.util.ToolResultBudgeter;
import com.wgh.rag.tool.definition.ToolDefinitionParser;
import com.wgh.rag.tool.entity.ToolConfig;
import com.wgh.rag.tool.executor.ToolExecutionEngine;
import com.wgh.rag.tool.service.ToolConfigService;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Agent 单实例执行器 —— 同步 ReAct tool-calling 循环 + 可选 RAG 前置注入 + 可选 Reflection 后置打分。
 * <p>
 * 语义上是 StepExecutor 的多 Agent 版本：额外需要携带 Agent 身份/目标 + 可选 Reflection + SSE 事件上报。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentInstanceExecutor {

    private static final int MAX_TOOL_ROUNDS = 5;

    private final ModelCacheManager modelCacheManager;
    private final ToolExecutionEngine toolExecutionEngine;
    private final com.wgh.rag.tool.fallback.ToolInvoker toolInvoker;
    private final ToolConfigService toolConfigService;
    private final RagRetrieveService ragRetrieveService;
    private final SseEventSender sse;
    private final RagProperties ragProperties;
    private final ModelFailoverService modelFailoverService;
    private final MultiAgentContextProperties contextProperties;

    /** 长期记忆（可选，单测中未注入时跳过读写） */
    @Autowired(required = false)
    private AgentTaskMemory agentTaskMemory;

    /**
     * 执行一次 Agent 调用（可能带 ReAct 循环），返回结构化结果。
     */
    public AgentInstanceResult execute(AgentInstance agent,
                                       String input,
                                       MultiAgentContext ctx,
                                       AgentEventStream emitter) {
        long t0 = System.currentTimeMillis();
        String runId = ctx != null ? String.valueOf(ctx.getOrDefault("runId", "")) : "";
        String agentId = agent != null ? agent.getAgentId() : "";

        List<AgentInstanceResult.ToolInvocation> toolInvocations = new ArrayList<>();
        List<AgentInstanceResult.RagSource> ragSources = new ArrayList<>();

        sse.send(emitter, MultiAgentEvents.AGENT_START,
                "runId", runId,
                "agentId", agentId,
                "agentName", agent != null ? agent.getName() : null,
                "role", agent != null ? agent.getRole() : null,
                "input", input);

        try {
            if (agent == null) {
                return failed("agent is null", t0);
            }

            // ========== 可用工具 ==========
            Map<String, ToolConfig> toolRegistry = new HashMap<>();
            List<ToolSpecification> toolSpecs = new ArrayList<>();
            if (agent.getToolIds() != null && !agent.getToolIds().isEmpty()) {
                try {
                    List<ToolConfig> configs = resolveToolConfigs(agent.getToolIds());
                    toolSpecs = toolExecutionEngine.loadWindowTools(configs, agent.getChatModelId());
                    for (ToolConfig cfg : configs) {
                        try {
                            ToolSpecification spec = ToolDefinitionParser.parse(cfg.getDefinition());
                            toolRegistry.put(spec.name(), cfg);
                        } catch (Exception ignore) {
                            // ToolDefinitionParser 已经在 loadWindowTools 阶段解析过，这里跳过重复解析
                        }
                    }
                } catch (Exception e) {
                    log.warn("Agent {} 工具加载失败: {}", agentId, e.getMessage());
                }
            }

            // ========== 可选 RAG ==========
            String enrichedInput = input == null ? "" : input;
            String systemPrompt = buildSystemPrompt(agent);
            // 长期记忆召回（任务 + 会话作用域），作为 FAR_FACT 片段进入统一预算
            String memoryBlock = buildMemoryBlock(ctx, input);
            if (!memoryBlock.isBlank()) {
                systemPrompt = systemPrompt + "\n\n" + memoryBlock;
            }
            if (agent.getKbIds() != null && !agent.getKbIds().isEmpty()
                    && enrichedInput != null && !enrichedInput.isBlank()) {
                List<EmbeddingMatch<TextSegment>> matches = ragRetrieveService.searchMultiple(
                        agent.getKbIds(), enrichedInput);
                // 多 Agent 链路拿不到模型配置，使用默认预算参数（兜底窗口 8192 + 硬上限 6000）
                RagContextBudgeter.Result budgetResult = RagContextBudgeter.trimWithDefaults(matches,
                        TokenEstimator.estimate(enrichedInput) + TokenEstimator.estimate(systemPrompt) + 32);
                matches = budgetResult.kept();
                log.info("Agent {} RAG 上下文预算: budget={}, 原始 {} 条/{} tokens, 保留 {} 条/{} tokens, 丢弃 {} 条, 截断={}",
                        agentId, budgetResult.budgetTokens(), budgetResult.originalCount(),
                        budgetResult.originalTokens(), budgetResult.keptCount(), budgetResult.keptTokens(),
                        budgetResult.dropped().size(), budgetResult.truncated());
                ragSources = buildRagSources(matches);
                String ragContext = extractRagContext(matches);
                if (!ragContext.isBlank()) {
                    enrichedInput = """
                            %s

                            ## 知识库检索结果（仅供参考，事实以检索原文为准）
                            %s
                            """.formatted(enrichedInput, ragContext);
                }
                sse.send(emitter, MultiAgentEvents.AGENT_RAG, Map.of(
                        "runId", runId, "agentId", agentId, "kbCount", matches.size()));
            }

            // ========== 初始 messages ==========
            List<ChatMessage> messages = new ArrayList<>();

            if (!systemPrompt.isBlank()) {
                messages.add(SystemMessage.from(systemPrompt));
            }
            // 可选：按 agentId 注入最近 N 轮记忆（受 Token 约束）
            if (ctx != null && agent.isHasMemory()) {
                appendRecentMemory(messages, ctx.getHistory(agentId));
            }
            messages.add(UserMessage.from(enrichedInput));
            ChatModel model = modelCacheManager.getChatModel(agent.getChatModelId());
            int toolRounds = 0;
            String finalText = null;
            // 单次 Agent 运行一份工具调用预算（请求数 + 总时长），跨轮共享
            com.wgh.rag.tool.fallback.ToolTaskBudget toolBudget = toolInvoker.newTaskBudget();

            // ========== ReAct 循环 ==========
            while (toolRounds <= MAX_TOOL_ROUNDS) {
                ChatRequest request = ChatRequest.builder()
                        .messages(new ArrayList<>(messages))
                        .toolSpecifications(toolSpecs)
                        .build();

                ChatResponse response = chatWithFailover(agent.getChatModelId(), request, !toolSpecs.isEmpty());
                AiMessage aiMessage = response.aiMessage();
                if (aiMessage == null) {
                    finalText = "";
                    break;
                }

                List<ToolExecutionRequest> toolCalls = aiMessage.toolExecutionRequests();
                if (toolCalls != null && !toolCalls.isEmpty() && toolRounds < MAX_TOOL_ROUNDS) {
                    toolRounds++;
                    messages.add(aiMessage);

                    // thought
                    String thoughtText = aiMessage.text();
                    List<ToolExecutionResultMessage> toolResults = new ArrayList<>();
                    for (ToolExecutionRequest req : toolCalls) {
                        sse.send(emitter, MultiAgentEvents.AGENT_TOOL_CALL_START,
                                "runId", runId, "agentId", agentId,
                                "toolName", req.name(), "arguments", req.arguments());

                        com.wgh.rag.tool.fallback.ToolInvocationOutcome outcome =
                                toolInvoker.invoke(req, toolRegistry, agent.getChatModelId(), toolBudget);
                        ToolExecutionResultMessage result = outcome.message();
                        long tLatency = outcome.latencyMs();

                        AgentInstanceResult.ToolInvocation inv = AgentInstanceResult.ToolInvocation.builder()
                                .toolName(req.name())
                                .arguments(req.arguments())
                                .result(result.text())
                                .success(outcome.success())
                                .latencyMs(tLatency)
                                .build();
                        toolInvocations.add(inv);
                        toolResults.add(result);

                        sse.send(emitter, MultiAgentEvents.AGENT_TOOL_CALL_RESULT,
                                "runId", runId, "agentId", agentId,
                                "toolName", req.name(),
                                "arguments", req.arguments(),
                                "result", result.text(),
                                "success", inv.isSuccess(),
                                "latencyMs", tLatency);
                    }
                    messages.addAll(toolResults);
                    // 工具结果纳入上下文预算：单条上限 + 多轮累计上限
                    messages = applyToolResultBudget(messages);
                    continue;
                }

                // 没有 tool calls —— 最终回答
                finalText = aiMessage.text();
                break;
            }

            if (finalText == null) finalText = "";

            // ========== 可选 Reflection ==========
            Double reflectionScore = null;
            String reflectionFeedback = null;
            Boolean reflectionPass = null;

            if (agent.isEnableReflection()) {
                sse.send(emitter, MultiAgentEvents.AGENT_REFLECTION, Map.of(
                        "runId", runId, "agentId", agentId, "phase", "start"));

                Long reflectModelId = agent.getReflectionModelId() != null
                        ? agent.getReflectionModelId() : agent.getChatModelId();
                ChatModel reflectionModel = modelCacheManager.getChatModel(reflectModelId);

                String reflectPrompt = """
                        你是一个输出质量审查员。请对以下 Agent 输出进行 1-10 打分并给出反馈。
                        返回严格 JSON：{"score": <double>, "feedback": "<string>"}。
                        
                        ## Agent Goal
                        %s
                        
                        ## 用户输入
                        %s
                        
                        ## Agent 输出
                        %s
                        """.formatted(agent.getGoal(), input, finalText);

                ChatResponse reflectResp = reflectionModel.chat(ChatRequest.builder()
                        .messages(List.of(SystemMessage.from("你是严格的输出质量审查员，只返回 JSON。"),
                                UserMessage.from(reflectPrompt))).build());
                String rawReflect = reflectResp.aiMessage() != null ? reflectResp.aiMessage().text() : "";
                // 简单 parse：取第一个 {...}
                Map<String, Object> parsed = parseSimpleJson(rawReflect);
                Object scoreObj = parsed.get("score");
                if (scoreObj instanceof Number n) {
                    reflectionScore = n.doubleValue();
                } else if (scoreObj != null) {
                    try { reflectionScore = Double.parseDouble(String.valueOf(scoreObj)); }
                    catch (NumberFormatException ignore) {}
                }
                reflectionFeedback = parsed.getOrDefault("feedback", "").toString();

                reflectionPass = reflectionScore != null && reflectionScore >= agent.getReflectionThreshold();

                sse.send(emitter, MultiAgentEvents.AGENT_REFLECTION,
                        "runId", runId, "agentId", agentId, "phase", "done",
                        "score", reflectionScore, "feedback", reflectionFeedback, "pass", reflectionPass);

                // 低于阈值重试一次 —— 把 feedback 作为额外提示拼到 user message 前面
                if (!Boolean.TRUE.equals(reflectionPass)) {
                    log.info("Agent {} reflection 低分 {}, 重试", agentId, reflectionScore);
                    messages.add(AiMessage.from(finalText));
                    messages.add(UserMessage.from("上一次输出质量不合格，请根据以下反馈重新回答：\n"
                            + reflectionFeedback + "\n\n原始用户输入：\n" + input));

                    ChatResponse retry = chatWithFailover(agent.getChatModelId(), ChatRequest.builder()
                            .messages(new ArrayList<>(messages))
                            .toolSpecifications(toolSpecs)
                            .build(), !toolSpecs.isEmpty());
                    if (retry.aiMessage() != null && retry.aiMessage().text() != null) {
                        finalText = retry.aiMessage().text();
                    }
                }
            }

            long duration = System.currentTimeMillis() - t0;
            AgentInstanceResult result = AgentInstanceResult.builder()
                    .output(finalText)
                    .toolInvocations(toolInvocations)
                    .ragSources(ragSources)
                    .reflectionScore(reflectionScore)
                    .reflectionFeedback(reflectionFeedback)
                    .reflectionPass(reflectionPass)
                    .durationMs(duration)
                    .card(buildCard(agentId, agent, finalText, ragSources, toolInvocations, reflectionScore))
                    .build();
            recordMemory(ctx, agent, input, finalText);
            rememberTaskMemory(ctx, agentId, finalText);

            sse.send(emitter, MultiAgentEvents.AGENT_DONE,
                    "runId", runId, "agentId", agentId,
                    "output", result.getOutput(),
                    "toolCount", toolInvocations.size(),
                    "ragCount", ragSources.size(),
                    "durationMs", duration);

            return result;

        } catch (Exception e) {
            log.error("Agent {} 执行异常", agentId, e);
            long duration = System.currentTimeMillis() - t0;
            String failedOutput = "执行失败：" + e.getMessage();
            AgentInstanceResult result = AgentInstanceResult.builder()
                    .output(failedOutput)
                    .toolInvocations(toolInvocations)
                    .ragSources(ragSources)
                    .durationMs(duration)
                    .card(buildCard(agentId, agent, failedOutput, ragSources, toolInvocations, null))
                    .build();
            sse.send(emitter, MultiAgentEvents.AGENT_DONE,
                    "runId", runId, "agentId", agentId,
                    "error", e.getMessage(), "durationMs", duration);
            return result;
        }
    }

    // ==================== 工具方法 ====================

    /**
     * 按链顺序调用模型；切链只重试「本次模型调用」，已执行工具的结果已在 messages 中，
     * 不会因为换模型而重复执行（避免副作用放大）。
     */
    private ChatResponse chatWithFailover(Long chatModelId, ChatRequest request, boolean requireTools) {
        List<LlmModel> chain = modelFailoverService != null
                ? modelFailoverService.resolveChain(chatModelId, null, requireTools) : List.of();
        if (chain == null || chain.isEmpty()) {
            return modelCacheManager.getChatModel(chatModelId).chat(request);
        }
        return BlockingModelFailover.call(chain, (attempt, candidate) ->
                modelCacheManager.getChatModel(candidate.getId()).chat(request),
                new BlockingModelFailover.Listener() {
                    @Override
                    public void onPreContentFailure(FailoverAttempt attempt, Throwable error) {
                        log.warn("[MultiAgent] 模型 {} 失败，切换备用（{}/{}）：{}",
                                attempt.model().getModelName(), attempt.index() + 1, attempt.total(),
                                error.getMessage());
                    }
                });
    }

    private AgentResultCard buildCard(String agentId, AgentInstance agent, String output,
                                      List<AgentInstanceResult.RagSource> ragSources,
                                      List<AgentInstanceResult.ToolInvocation> toolInvocations,
                                      Double reflectionScore) {
        return AgentResultCardBuilder.build(agentId, agent != null ? agent.getName() : null, output,
                ragSources, toolInvocations, reflectionScore,
                contextProperties.getCardConclusionMaxTokens());
    }

    /** 注入最近 N 条记忆，超出 Token 预算时从最旧开始丢弃。 */
    private void appendRecentMemory(List<ChatMessage> messages, List<Object> history) {
        if (history == null || history.isEmpty()) {
            return;
        }
        List<ChatMessage> recent = new ArrayList<>();
        synchronized (history) {
            int from = Math.max(history.size() - contextProperties.getMemoryMaxMessages(), 0);
            for (int i = from; i < history.size(); i++) {
                if (history.get(i) instanceof ChatMessage message) {
                    recent.add(message);
                }
            }
        }
        int budget = Math.max(contextProperties.getMemoryMaxTokens(), 0);
        int total = recent.stream().mapToInt(m -> TokenEstimator.estimate(messageText(m))).sum();
        while (!recent.isEmpty() && total > budget) {
            total -= TokenEstimator.estimate(messageText(recent.remove(0)));
        }
        messages.addAll(recent);
    }

    private String messageText(ChatMessage message) {
        if (message instanceof UserMessage user) {
            return user.singleText();
        }
        if (message instanceof AiMessage ai) {
            return ai.text() == null ? "" : ai.text();
        }
        return "";
    }

    /** 召回任务/会话长期记忆；失败或无命中返回空串。 */
    private String buildMemoryBlock(MultiAgentContext ctx, String input) {
        if (agentTaskMemory == null || ctx == null || input == null || input.isBlank()) {
            return "";
        }
        return agentTaskMemory.recallBlock(taskId(ctx), sessionId(ctx), input);
    }

    /** 写入任务级记忆（TASK 作用域 + 会话镜像），失败只记日志。 */
    private void rememberTaskMemory(MultiAgentContext ctx, String agentId, String finalText) {
        if (agentTaskMemory == null || ctx == null || finalText == null || finalText.isBlank()) {
            return;
        }
        agentTaskMemory.rememberTask(taskId(ctx), sessionId(ctx), agentId, "SUMMARY", finalText);
    }

    private static String taskId(MultiAgentContext ctx) {
        Object value = ctx.get("runId");
        if (value == null) return null;
        String id = String.valueOf(value);
        return id.isBlank() ? null : id;
    }

    private static Long sessionId(MultiAgentContext ctx) {
        Object value = ctx.get("sessionId");
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(String.valueOf(value));
            } catch (NumberFormatException ignore) {
                return null;
            }
        }
        return null;
    }

    /** 成功轮次写回 agentId 历史，并保持条数上限。 */
    private void recordMemory(MultiAgentContext ctx, AgentInstance agent, String input, String output) {
        if (ctx == null || agent == null || !agent.isHasMemory() || input == null) {
            return;
        }
        List<Object> history = ctx.getHistory(agent.getAgentId());
        synchronized (history) {
            history.add(UserMessage.from(input));
            if (output != null) {
                history.add(AiMessage.from(output));
            }
            int max = Math.max(contextProperties.getMemoryMaxMessages() * 2, 2);
            while (history.size() > max) {
                history.remove(0);
            }
        }
    }

    /** 按配置对消息中的工具结果做「单条 + 累计」Token 裁剪，未超限时原样返回。 */
    private List<ChatMessage> applyToolResultBudget(List<ChatMessage> messages) {
        RagProperties.Context props = ragProperties != null ? ragProperties.getContext() : null;
        int perResult = props != null && props.getToolResultMaxTokens() != null
                ? props.getToolResultMaxTokens() : ToolResultBudgeter.DEFAULT_TOOL_RESULT_MAX_TOKENS;
        int roundBudget = props != null && props.getToolRoundHistoryMaxTokens() != null
                ? props.getToolRoundHistoryMaxTokens() : ToolResultBudgeter.DEFAULT_TOOL_ROUND_HISTORY_MAX_TOKENS;
        ToolResultBudgeter.Result result = ToolResultBudgeter.trim(messages, perResult, roundBudget);
        if (!result.changed()) {
            return messages;
        }
        log.info("AgentInstanceExecutor 工具结果上下文预算: 单条上限={}, 累计上限={}, 原始 {} tokens, 保留 {} tokens, 截断 {} 条, 折叠 {} 条",
                perResult, roundBudget, result.originalTokens(), result.keptTokens(),
                result.truncatedCount(), result.collapsedCount());
        return result.messages();
    }

    private AgentInstanceResult failed(String msg, long t0) {
        return AgentInstanceResult.builder()
                .output("执行失败：" + msg)
                .durationMs(System.currentTimeMillis() - t0)
                .build();
    }

    private String buildSystemPrompt(AgentInstance agent) {
        StringBuilder sb = new StringBuilder();
        if (agent.getSystemPrompt() != null && !agent.getSystemPrompt().isBlank()) {
            sb.append(agent.getSystemPrompt().trim());
        }
        sb.append("\n\n你是一个多Agent协作中的角色");
        if (agent.getRole() != null && !agent.getRole().isBlank()) {
            sb.append("（").append(agent.getRole()).append("）");
        }
        sb.append("。");
        if (agent.getGoal() != null && !agent.getGoal().isBlank()) {
            sb.append("你的目标是：").append(agent.getGoal().trim());
        }
        return sb.toString();
    }

    private List<ToolConfig> resolveToolConfigs(List<String> toolIds) {
        List<ToolConfig> result = new ArrayList<>();
        if (toolIds == null) return result;
        for (String raw : toolIds) {
            if (raw == null || raw.isBlank()) continue;
            ToolConfig cfg = null;
            try {
                cfg = toolConfigService.getById(Long.parseLong(raw.trim()));
            } catch (NumberFormatException ignore) {
                // 按名称查找
                List<ToolConfig> byName = toolConfigService.listByType(null);
                for (ToolConfig c : byName) {
                    if (raw.equals(c.getName())) { cfg = c; break; }
                }
            } catch (Exception e) {
                log.warn("加载工具失败 {}: {}", raw, e.getMessage());
            }
            if (cfg != null) result.add(cfg);
        }
        return result;
    }

    private List<AgentInstanceResult.RagSource> buildRagSources(List<EmbeddingMatch<TextSegment>> matches) {
        List<AgentInstanceResult.RagSource> sources = new ArrayList<>();
        if (matches == null) return sources;
        for (EmbeddingMatch<TextSegment> m : matches) {
            TextSegment seg = m.embedded();
            String fileName = seg != null && seg.metadata() != null ? seg.metadata().getString("fileName") : null;
            Integer chunkIndex = seg != null && seg.metadata() != null ? seg.metadata().getInteger("chunkIndex") : null;
            sources.add(AgentInstanceResult.RagSource.builder()
                    .fileName(fileName)
                    .chunkIndex(chunkIndex)
                    .score(m.score())
                    .content(seg != null ? seg.text() : null)
                    .build());
        }
        return sources;
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
                sb.append("【来源: ").append(kbName != null ? kbName : "")
                        .append(" - ").append(fileName != null ? fileName : "")
                        .append("】\n");
            }
            sb.append(segment.text()).append("\n\n");
        }
        return sb.toString();
    }

    /** 从 LLM 返回里解析 {"score":..., "feedback":...} 简单 JSON */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseSimpleJson(String raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) return result;
        // 去掉代码围栏
        String cleaned = raw.trim();
        if (cleaned.startsWith("```")) {
            int end = cleaned.indexOf("```", 3);
            if (end > 0) cleaned = cleaned.substring(cleaned.indexOf('\n', 3) + 1, end);
        }
        // 找 {..} 区间
        int left = cleaned.indexOf('{');
        int right = cleaned.lastIndexOf('}');
        if (left < 0 || right < 0 || right <= left) return result;
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                    cleaned.substring(left, right + 1), new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (Exception ignore) {
            return result;
        }
    }
}
