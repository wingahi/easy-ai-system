package com.wgh.rag.agentmulti.support;

import com.wgh.rag.agentmulti.constant.MultiAgentEvents;
import com.wgh.rag.agentmulti.context.MultiAgentContext;
import com.wgh.rag.agentmulti.dto.AgentInstance;
import com.wgh.rag.agentmulti.dto.AgentInstanceResult;
import com.wgh.rag.kb.service.RagRetrieveService;
import com.wgh.rag.llm.factory.ModelCacheManager;
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
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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
    private final ToolConfigService toolConfigService;
    private final RagRetrieveService ragRetrieveService;
    private final SseEventSender sse;

    /**
     * 执行一次 Agent 调用（可能带 ReAct 循环），返回结构化结果。
     */
    public AgentInstanceResult execute(AgentInstance agent,
                                       String input,
                                       MultiAgentContext ctx,
                                       SseEmitter emitter) {
        long t0 = System.currentTimeMillis();
        String runId = ctx != null ? String.valueOf(ctx.getOrDefault("runId", "")) : "";
        String agentId = agent != null ? agent.getAgentId() : "";

        List<AgentInstanceResult.ToolInvocation> toolInvocations = new ArrayList<>();
        List<AgentInstanceResult.RagSource> ragSources = new ArrayList<>();

        sse.send(emitter, MultiAgentEvents.AGENT_START, Map.of(
                "runId", runId,
                "agentId", agentId,
                "agentName", agent != null ? agent.getName() : null,
                "role", agent != null ? agent.getRole() : null,
                "input", input));

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
            if (agent.getKbIds() != null && !agent.getKbIds().isEmpty()
                    && enrichedInput != null && !enrichedInput.isBlank()) {
                List<EmbeddingMatch<TextSegment>> matches = ragRetrieveService.searchMultiple(
                        agent.getKbIds(), enrichedInput);
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

            String systemPrompt = buildSystemPrompt(agent);
            if (!systemPrompt.isBlank()) {
                messages.add(SystemMessage.from(systemPrompt));
            }
            messages.add(UserMessage.from(enrichedInput));

            // 可选：从 ctx 读取 agentId 历史（GroupChat 场景）
            if (ctx != null && agent.isHasMemory()) {
                List<Object> hist = ctx.getHistory(agentId);
                // 历史会在后续循环中逐步追加，初始仅放 system+user
            }

            ChatModel model = modelCacheManager.getChatModel(agent.getChatModelId());
            int toolRounds = 0;
            String finalText = null;

            // ========== ReAct 循环 ==========
            while (toolRounds <= MAX_TOOL_ROUNDS) {
                ChatRequest request = ChatRequest.builder()
                        .messages(new ArrayList<>(messages))
                        .toolSpecifications(toolSpecs)
                        .build();

                ChatResponse response = model.chat(request);
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
                        sse.send(emitter, MultiAgentEvents.AGENT_TOOL_CALL_START, Map.of(
                                "runId", runId, "agentId", agentId,
                                "toolName", req.name(), "arguments", req.arguments()));

                        long tStart = System.currentTimeMillis();
                        ToolExecutionResultMessage result;
                        try {
                            result = toolExecutionEngine.execute(req, toolRegistry, agent.getChatModelId());
                        } catch (Exception e) {
                            log.error("工具执行异常 tool={}", req.name(), e);
                            result = new ToolExecutionResultMessage(
                                    req.id(), req.name(), "Error: " + e.getMessage());
                        }
                        long tLatency = System.currentTimeMillis() - tStart;

                        AgentInstanceResult.ToolInvocation inv = AgentInstanceResult.ToolInvocation.builder()
                                .toolName(req.name())
                                .arguments(req.arguments())
                                .result(result.text())
                                .success(!result.text().startsWith("Error:"))
                                .latencyMs(tLatency)
                                .build();
                        toolInvocations.add(inv);
                        toolResults.add(result);

                        sse.send(emitter, MultiAgentEvents.AGENT_TOOL_CALL_RESULT, Map.of(
                                "runId", runId, "agentId", agentId,
                                "toolName", req.name(),
                                "arguments", req.arguments(),
                                "result", result.text(),
                                "success", inv.isSuccess(),
                                "latencyMs", tLatency));
                    }
                    messages.addAll(toolResults);
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

                sse.send(emitter, MultiAgentEvents.AGENT_REFLECTION, Map.of(
                        "runId", runId, "agentId", agentId, "phase", "done",
                        "score", reflectionScore, "feedback", reflectionFeedback, "pass", reflectionPass));

                // 低于阈值重试一次 —— 把 feedback 作为额外提示拼到 user message 前面
                if (!Boolean.TRUE.equals(reflectionPass)) {
                    log.info("Agent {} reflection 低分 {}, 重试", agentId, reflectionScore);
                    messages.add(AiMessage.from(finalText));
                    messages.add(UserMessage.from("上一次输出质量不合格，请根据以下反馈重新回答：\n"
                            + reflectionFeedback + "\n\n原始用户输入：\n" + input));

                    ChatResponse retry = model.chat(ChatRequest.builder()
                            .messages(new ArrayList<>(messages))
                            .toolSpecifications(toolSpecs)
                            .build());
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
                    .build();

            sse.send(emitter, MultiAgentEvents.AGENT_DONE, Map.of(
                    "runId", runId, "agentId", agentId,
                    "output", result.getOutput(),
                    "toolCount", toolInvocations.size(),
                    "ragCount", ragSources.size(),
                    "durationMs", duration));

            return result;

        } catch (Exception e) {
            log.error("Agent {} 执行异常", agentId, e);
            long duration = System.currentTimeMillis() - t0;
            AgentInstanceResult result = AgentInstanceResult.builder()
                    .output("执行失败：" + e.getMessage())
                    .toolInvocations(toolInvocations)
                    .ragSources(ragSources)
                    .durationMs(duration)
                    .build();
            sse.send(emitter, MultiAgentEvents.AGENT_DONE, Map.of(
                    "runId", runId, "agentId", agentId,
                    "error", e.getMessage(), "durationMs", duration));
            return result;
        }
    }

    // ==================== 工具方法 ====================

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
