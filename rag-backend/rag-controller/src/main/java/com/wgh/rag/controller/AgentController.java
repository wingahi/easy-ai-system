package com.wgh.rag.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wgh.rag.agent.config.AgentProperties;
import com.wgh.rag.agent.dto.AgentStepResult;
import com.wgh.rag.agent.entity.AgentPlan;
import com.wgh.rag.agent.entity.AgentStep;
import com.wgh.rag.agent.mapper.AgentPlanMapper;
import com.wgh.rag.agent.mapper.AgentStepMapper;
import com.wgh.rag.agent.service.AgentOrchestrator;
import com.wgh.rag.common.result.Result;
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
public class AgentController {

    private final AgentOrchestrator agentOrchestrator;
    private final AgentPlanMapper planMapper;
    private final AgentStepMapper stepMapper;
    private final AgentProperties agentProperties;
    private final com.wgh.rag.chat.service.RagChatService ragChatService;

    /** 查询 plan 当前状态（刷新页面恢复面板用）。 */
    @GetMapping("/plans/{planId}/state")
    public Result<Map<String, Object>> getPlanState(@PathVariable Long planId) {
        AgentPlan plan = planMapper.selectById(planId);
        if (plan == null) return Result.fail("Plan 不存在");

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

        String userInput = body != null ? body.getOrDefault("userInput", "") : "";
        double threshold = planMapper.selectById(plan.getId()) != null
                ? agentProperties.getDefaultReflectionThreshold() : 7.0;

        // 用 no-op sink（REST 不走 SSE）
        AgentStepResult result = agentOrchestrator.resume(
                plan.getId(), userInput,
                threshold, agentProperties.getDefaultReflectionRetries(),
                null);

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
        agentOrchestrator.cancel(planId, null);
        AgentPlan plan = planMapper.selectById(planId);
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
        AgentStepResult result = agentOrchestrator.retryStep(
                plan.getId(), step.getStepId(),
                agentProperties.getDefaultReflectionThreshold(),
                agentProperties.getDefaultReflectionRetries(),
                null);

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
}
