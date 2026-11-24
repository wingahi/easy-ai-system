package com.wgh.rag.controller.agent;

import com.wgh.rag.agent.config.AgentProperties;
import com.wgh.rag.agent.dto.AgentStepResult;
import com.wgh.rag.agent.entity.AgentPlan;
import com.wgh.rag.agent.service.AgentHitlConfirmService;
import com.wgh.rag.agent.service.AgentOrchestrator;
import com.wgh.rag.chat.entity.ChatWindow;
import com.wgh.rag.chat.service.ChatWindowService;
import com.wgh.rag.chat.service.RagChatService;
import com.wgh.rag.llm.entity.LlmModel;
import com.wgh.rag.llm.service.ModelRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * HITL 确认续跑。
 *
 * <p>确认（状态推进 + 扣费闸门）与续跑在同一入口完成，供 REST 同步降级与
 * {@code AGENT_PLAN} 后台作业复用。恢复入口没有实时窗口上下文，按 {@code plan.chatWindowId}
 * 回查窗口路由组，保持与首轮一致的切链口径。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentHitlResumeService {

    private final AgentOrchestrator agentOrchestrator;

    private final AgentHitlConfirmService agentHitlConfirmService;

    private final AgentProperties agentProperties;

    private final RagChatService ragChatService;

    private final ChatWindowService chatWindowService;

    private final ModelRouter modelRouter;

    /**
     * 确认并续跑。
     *
     * @param stepId    步骤主键
     * @param userInput 用户补充输入，可为空
     * @return planId / stepId / resultStatus / finalText
     */
    public Map<String, Object> resume(Long stepId, String userInput) {
        double threshold = agentProperties.getDefaultReflectionThreshold();
        AgentHitlConfirmService.Claim claim = agentHitlConfirmService.prepare(stepId, userInput);
        AgentPlan plan = claim.plan();
        AgentStepResult result;
        WindowChains chains = resolveWindowChains(plan);
        try {
            result = agentOrchestrator.resumeConfirmed(
                    plan, claim.step(), userInput,
                    threshold, agentProperties.getDefaultReflectionRetries(),
                    null, chains.executor(), chains.planner());
        } catch (RuntimeException e) {
            // 执行失败且未产出：立即退款，补偿作业兜底
            claim.billing().finish();
            throw e;
        }

        Map<String, Object> resp = new HashMap<>();
        resp.put("planId", plan.getId());
        resp.put("stepId", stepId);
        resp.put("resultStatus", result != null ? result.getStatus() : "PAUSED");
        String finalText = result != null && result.getFinalText() != null ? result.getFinalText() : "";
        resp.put("finalText", finalText);
        if (!finalText.isBlank()) {
            // 最终答复即有效产出：结算本次续跑计费
            claim.billing().markOutput();
            // 后台作业没有 SSE，需把恢复执行后的最终答复补写回聊天会话
            ragChatService.appendAssistantMessage(plan.getSessionId(), finalText);
        }
        if (result == null) {
            // 再次停在 HITL：不算失败，保持未结算，等下一次续跑复用
            claim.billing().finishOnPause();
        } else {
            claim.billing().finish();
        }
        return resp;
    }

    private WindowChains resolveWindowChains(AgentPlan plan) {
        ChatWindow window = plan == null ? null : chatWindowService.findById(plan.getChatWindowId());
        Long executorModelId = window != null && window.getAgentExecutorModelId() != null
                ? window.getAgentExecutorModelId()
                : (window != null && window.getLlmModelId() != null ? window.getLlmModelId() : plan.getLlmModelId());
        Long plannerModelId = window != null && window.getAgentPlannerModelId() != null
                ? window.getAgentPlannerModelId() : executorModelId;
        String routeGroup = window != null ? window.getModelRouteGroup() : null;
        return new WindowChains(
                modelRouter.resolveChain(routeGroup, executorModelId),
                modelRouter.resolveChain(routeGroup, plannerModelId));
    }

    private record WindowChains(List<LlmModel> executor, List<LlmModel> planner) {
    }
}
