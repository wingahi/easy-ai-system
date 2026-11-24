package com.wgh.rag.chat.service.flow;

import com.wgh.rag.agent.dto.AgentStepResult.RagSourceRecord;
import com.wgh.rag.agent.service.AgentOrchestrator;
import com.wgh.rag.chat.service.prompt.ChatCitationAssembler;
import com.wgh.rag.chat.service.session.ChatMessageStore;
import com.wgh.rag.common.metrics.RagMetrics;
import com.wgh.rag.llm.entity.LlmModel;
import com.wgh.rag.llm.service.ModelRouter;
import dev.langchain4j.agent.tool.ToolSpecification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 路线：窗口启用 Agent 自动规划执行时走 Planner/Executor 编排。
 *
 * <p>执行失败时降级为普通 RAG（返回 false，继续后续步骤），与原行为一致。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
final class AgentChatStep implements ChatTurnStep {

    private final AgentOrchestrator agentOrchestrator;
    private final com.wgh.rag.agent.config.AgentProperties agentProperties;
    private final ModelRouter modelRouter;
    private final ChatMessageStore messageStore;
    private final RagMetrics metrics;

    @Override
    public int order() {
        return 400;
    }

    @Override
    public boolean execute(ChatTurnContext ctx) throws Exception {
        if (!ctx.agentEnabled) {
            return false;
        }

        log.info("Agent 分支检查: windowId={}, agentEnabled={}, toolSpecsSize={}, hasKb={}",
                ctx.window.getId(), ctx.window.getAgentEnabled(), ctx.toolSpecs.size(), ctx.hasKb);
        try {
            // SSE 事件回调：AgentOrchestrator.emit(eventName, data) → 拆成独立 SSE 事件名
            java.util.function.BiConsumer<String, Map<String, Object>> sseEventSink = (eventName, data) -> {
                try {
                    ctx.events.send(eventName, data);
                } catch (Exception se) {
                    // 客户端刷新/断开时 send 会抛 IOException 或 IllegalStateException，
                    // 此时 Agent 仍应继续执行，最终结果会落库
                    log.warn("Agent SSE 事件发送失败 [{}]: {}", eventName, se.getMessage());
                }
            };

            List<String> toolNames = ctx.toolSpecs.stream()
                    .map(ToolSpecification::name).toList();

            Long windowModelId = ctx.window.getLlmModelId();
            Long plannerModelId = ctx.window.getAgentPlannerModelId() != null
                    ? ctx.window.getAgentPlannerModelId() : windowModelId;
            Long executorModelId = ctx.window.getAgentExecutorModelId() != null
                    ? ctx.window.getAgentExecutorModelId() : windowModelId;
            double reflectionThreshold = ctx.window.getAgentReflectionThreshold() != null
                    ? ctx.window.getAgentReflectionThreshold() : agentProperties.getDefaultReflectionThreshold();
            int reflectionMaxRetries = ctx.window.getAgentMaxSteps() != null
                    ? Math.min(ctx.window.getAgentMaxSteps(), 5)
                    : agentProperties.getDefaultReflectionRetries();
            boolean hitlEnabled = Integer.valueOf(1).equals(ctx.window.getAgentHitlEnabled());

            // 与普通 RAG 保持同一故障转移口径：优先窗口 route_group，否则模型自身路由组/单模型
            List<LlmModel> executorChain = modelRouter.resolveChain(
                    ctx.window.getModelRouteGroup(), executorModelId);
            List<LlmModel> plannerChain = modelRouter.resolveChain(
                    ctx.window.getModelRouteGroup(), plannerModelId);

            com.wgh.rag.agent.dto.AgentStepResult agentResult = agentOrchestrator.orchestrate(
                    ctx.session.getId(),
                    ctx.window.getId(),
                    executorModelId,
                    plannerModelId,
                    ctx.userMessage,
                    toolNames,
                    ctx.toolSpecs,
                    ctx.toolRegistry,
                    ctx.hasKb,
                    ctx.kbIds,
                    reflectionThreshold,
                    reflectionMaxRetries,
                    hitlEnabled,
                    sseEventSink,
                    executorChain,
                    plannerChain);

            // 先落库，再尽力下发 SSE：用户刷新页面导致发送失败时不能丢掉 Agent 结果
            String agentFinalText = agentResult != null && agentResult.getFinalText() != null
                    ? agentResult.getFinalText() : "";
            List<RagSourceRecord> agentSources = agentResult != null && agentResult.getRagSources() != null
                    ? agentResult.getRagSources() : List.of();
            List<Map<String, Object>> sourceList = ChatCitationAssembler.buildSourcesListFromRecords(agentSources);
            Double agentAccuracy = ctx.hasKb
                    ? ChatCitationAssembler.calculateAccuracyRateFromRecords(agentFinalText, agentSources) : null;

            if (!agentFinalText.isBlank()) {
                messageStore.saveAssistantMessage(ctx.session, agentFinalText, sourceList,
                        ctx.hitRate, agentAccuracy, null, null);
                // Agent 链路不逐字下发 content，最终答复即有效产出
                ctx.markOutput();
            } else {
                log.warn("Agent 未产出最终文本（可能 HITL 暂停），跳过助手消息落库: session={}",
                        ctx.session.getId());
            }

            ctx.events.sendQuietly("sources", sourceList);
            if (agentAccuracy != null) {
                ctx.events.sendQuietly("accuracy", Map.of("accuracyRate", agentAccuracy));
            }

            Map<String, Object> doneData = new HashMap<>();
            doneData.put("fullResponse", agentFinalText);
            if (agentAccuracy != null) doneData.put("accuracyRate", agentAccuracy);
            ctx.events.sendQuietly("done", doneData);
            ctx.events.completeQuietly();
            ctx.metricCtx.route().set("agent");
            return true;
        } catch (Exception ex) {
            metrics.recordChatError("agent");
            log.warn("Agent 分支启动/执行失败，降级为普通 RAG: {}", ex.getMessage());
            return false;
        }
    }
}
