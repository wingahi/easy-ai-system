package com.wgh.rag.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wgh.rag.agent.config.AgentProperties;
import com.wgh.rag.agent.dto.AgentStepResult;
import com.wgh.rag.agent.entity.AgentPlan;
import com.wgh.rag.agent.entity.AgentStep;
import com.wgh.rag.agent.mapper.AgentPlanMapper;
import com.wgh.rag.agent.mapper.AgentStepMapper;
import com.wgh.rag.agent.service.AgentOrchestrator;
import com.wgh.rag.auth.constant.AuthConstants;
import com.wgh.rag.chat.entity.ChatWindow;
import com.wgh.rag.chat.service.ChatWindowService;
import com.wgh.rag.llm.entity.LlmModel;
import com.wgh.rag.llm.service.ModelRouter;
import com.wgh.rag.common.constant.RoleCodes;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.result.Result;
import com.wgh.rag.common.result.ResultCode;
import com.wgh.rag.common.security.LoginUser;
import com.wgh.rag.common.security.RequiresPermission;
import com.wgh.rag.common.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Agent 执行控制 API — HITL 确认 / cancel / retry / skip / 状态查询。
 * 流式主链路由 ChatController.stream 入口走 AgentOrchestrator.orchestrate()，
 * 这些 REST 接口用于中断后的控制操作和状态恢复。
 */
@Slf4j
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
@RequiresPermission(AuthConstants.PERM_AGENT_USE)
public class AgentController {

    private final AgentOrchestrator agentOrchestrator;
    private final AgentPlanMapper planMapper;
    private final AgentStepMapper stepMapper;
    private final AgentProperties agentProperties;
    private final com.wgh.rag.chat.service.RagChatService ragChatService;
    private final ChatWindowService chatWindowService;
    private final ModelRouter modelRouter;

    /** 查询 plan 当前状态（刷新页面恢复面板用）。 */
    @GetMapping("/plans/{planId}/state")
    public Result<Map<String, Object>> getPlanState(@PathVariable Long planId) {
        AgentPlan plan = planMapper.selectById(planId);
        if (plan == null) return Result.fail("Plan 不存在");
        assertPlanAccessible(plan);

        List<AgentStep> steps = stepMapper.selectList(
                new LambdaQueryWrapper<AgentStep>()
                        .eq(AgentStep::getPlanId, planId)
                        .eq(AgentStep::getDeleted, 0)
                        .orderByAsc(AgentStep::getStepIndex));

        List<Map<String, Object>> stepList = steps.stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId());
            m.put("stepIndex", s.getStepIndex());
            m.put("stepId", s.getStepId());
            m.put("title", s.getTitle());
            m.put("description", s.getDescription());
            m.put("dependsOn", s.getDependsOn());
            m.put("status", s.getStatus());
            m.put("reflectionScore", s.getReflectionScore());
            m.put("reflectionFeedback", s.getReflectionFeedback());
            m.put("retryCount", s.getRetryCount());
            m.put("resultSummary", s.getResultSummary());
            m.put("thought", s.getThought());
            m.put("startTime", s.getStartTime());
            m.put("endTime", s.getEndTime());
            return m;
        }).toList();

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("planId", plan.getId());
        state.put("status", plan.getStatus());
        state.put("currentStepIndex", plan.getCurrentStepIndex());
        state.put("totalSteps", plan.getMaxSteps());
        state.put("userGoal", plan.getUserGoal());
        state.put("steps", stepList);
        state.put("createTime", plan.getCreateTime());
        state.put("finishTime", plan.getFinishTime());
        return Result.success(state);
    }

    /** HITL 步骤用户确认后继续执行。 */
    @PostMapping("/steps/{stepId}/hitl-confirm")
    public Result<Map<String, Object>> hitlConfirm(
            @PathVariable Long stepId,
            @RequestBody(required = false) Map<String, String> body) {
        AgentStep step = stepMapper.selectById(stepId);
        if (step == null) return Result.fail("Step 不存在");
        if (!"HITL_PENDING".equals(step.getStatus())) return Result.fail("Step 非 HITL_PENDING 状态");

        AgentPlan plan = planMapper.selectById(step.getPlanId());
        if (plan == null) return Result.fail("Plan 不存在");
        assertPlanAccessible(plan);

        String userInput = body != null ? body.getOrDefault("userInput", "") : "";
        double threshold = planMapper.selectById(plan.getId()) != null
                ? agentProperties.getDefaultReflectionThreshold() : 7.0;

        // 恢复入口没有实时窗口上下文，按 plan.chatWindowId 回查窗口路由组，保持与首轮一致的切链口径
        WindowChains chains = resolveWindowChains(plan);

        // 用 no-op sink（REST 不走 SSE）
        AgentStepResult result = agentOrchestrator.resume(
                plan.getId(), userInput,
                threshold, agentProperties.getDefaultReflectionRetries(),
                null, chains.executor(), chains.planner());

        Map<String, Object> resp = new HashMap<>();
        resp.put("planId", plan.getId());
        resp.put("stepId", stepId);
        resp.put("resultStatus", result != null ? result.getStatus() : "PAUSED");
        String finalText = result != null && result.getFinalText() != null ? result.getFinalText() : "";
        resp.put("finalText", finalText);
        // REST 通道没有 SSE，需把恢复执行后的最终答复补写回聊天会话
        if (!finalText.isBlank()) {
            ragChatService.appendAssistantMessage(plan.getSessionId(), finalText);
        }
        return Result.success(resp);
    }

    /** 取消整个计划。 */
    @PostMapping("/plans/{planId}/cancel")
    public Result<Map<String, Object>> cancel(@PathVariable Long planId) {
        AgentPlan plan = planMapper.selectById(planId);
        assertPlanAccessible(plan);
        agentOrchestrator.cancel(planId, null);
        Map<String, Object> resp = new HashMap<>();
        resp.put("planId", planId);
        resp.put("status", plan != null ? plan.getStatus() : "CANCELLED");
        return Result.success(resp);
    }

    /** 重试单个步骤（仅 FAILED / SKIPPED 状态可用）。 */
    @PostMapping("/steps/{stepId}/retry")
    public Result<Map<String, Object>> retryStep(@PathVariable Long stepId) {
        AgentStep step = stepMapper.selectById(stepId);
        if (step == null) return Result.fail("Step 不存在");

        AgentPlan plan = planMapper.selectById(step.getPlanId());
        assertPlanAccessible(plan);
        WindowChains chains = resolveWindowChains(plan);
        AgentStepResult result = agentOrchestrator.retryStep(
                plan.getId(), step.getStepId(),
                agentProperties.getDefaultReflectionThreshold(),
                agentProperties.getDefaultReflectionRetries(),
                null, chains.executor(), chains.planner());

        Map<String, Object> resp = new HashMap<>();
        resp.put("planId", plan.getId());
        resp.put("stepId", stepId);
        resp.put("resultStatus", result != null ? result.getStatus() : "UNKNOWN");
        resp.put("finalText", result != null ? result.getFinalText() : "");
        return Result.success(resp);
    }

    /** 跳过单个步骤。 */
    @PostMapping("/steps/{stepId}/skip")
    public Result<Map<String, Object>> skipStep(@PathVariable Long stepId) {
        AgentStep step = stepMapper.selectById(stepId);
        if (step == null) return Result.fail("Step 不存在");
        assertPlanAccessible(planMapper.selectById(step.getPlanId()));
        agentOrchestrator.skipStep(step.getPlanId(), step.getStepId(), null);

        Map<String, Object> resp = new HashMap<>();
        resp.put("planId", step.getPlanId());
        resp.put("stepId", stepId);
        resp.put("status", "SKIPPED");
        return Result.success(resp);
    }

    /** 按 sessionId 查询最新 plan（页面刷新恢复面板用）。 */
    @GetMapping("/sessions/{sessionId}/plan")
    public Result<Map<String, Object>> getPlanBySession(@PathVariable Long sessionId) {
        AgentPlan plan = planMapper.selectOne(
                new LambdaQueryWrapper<AgentPlan>()
                        .eq(AgentPlan::getSessionId, sessionId)
                        .eq(AgentPlan::getDeleted, 0)
                        .orderByDesc(AgentPlan::getId)
                        .last("LIMIT 1"));
        if (plan == null) return Result.success(null);
        assertPlanAccessible(plan);

        List<AgentStep> steps = stepMapper.selectList(
                new LambdaQueryWrapper<AgentStep>()
                        .eq(AgentStep::getPlanId, plan.getId())
                        .eq(AgentStep::getDeleted, 0)
                        .orderByAsc(AgentStep::getStepIndex));

        List<Map<String, Object>> stepList = steps.stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId());
            m.put("stepIndex", s.getStepIndex());
            m.put("stepId", s.getStepId());
            m.put("title", s.getTitle());
            m.put("description", s.getDescription());
            m.put("dependsOn", s.getDependsOn());
            m.put("status", s.getStatus());
            m.put("reflectionScore", s.getReflectionScore());
            m.put("reflectionFeedback", s.getReflectionFeedback());
            m.put("retryCount", s.getRetryCount());
            m.put("resultSummary", s.getResultSummary());
            m.put("startTime", s.getStartTime());
            m.put("endTime", s.getEndTime());
            return m;
        }).toList();

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("planId", plan.getId());
        state.put("status", plan.getStatus());
        state.put("userGoal", plan.getUserGoal());
        state.put("steps", stepList);
        state.put("currentStepIndex", plan.getCurrentStepIndex());
        state.put("createTime", plan.getCreateTime());
        return Result.success(state);
    }

    /** 恢复入口的模型链路：优先窗口 route_group，其次窗口/计划的模型配置。 */
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

    /**
     * 计划归属校验：有会话时按会话归属（窗口 + 用户）校验，
     * 无会话的历史计划按创建人匹配，无归属记录仅管理员可访问。
     */
    private void assertPlanAccessible(AgentPlan plan) {
        if (plan == null) return;
        if (plan.getSessionId() != null && plan.getChatWindowId() != null) {
            chatWindowService.getOwnedSession(
                    plan.getChatWindowId(), plan.getSessionId(), SecurityUtils.currentUsername());
            return;
        }
        String username = SecurityUtils.currentUsername();
        if (username.equals(plan.getCreateBy()) || isAdmin()) {
            return;
        }
        throw new BusinessException(ResultCode.PERMISSION_DENIED, "无权访问该计划");
    }

    private boolean isAdmin() {
        LoginUser user = LoginUser.get();
        return user != null && user.getRoles() != null && user.getRoles().contains(RoleCodes.ADMIN);
    }
}
