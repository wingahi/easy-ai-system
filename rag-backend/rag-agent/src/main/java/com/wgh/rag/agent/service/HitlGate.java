package com.wgh.rag.agent.service;

import com.wgh.rag.agent.constant.AgentEvents;
import com.wgh.rag.agent.dto.PlanDTO.StepDTO;
import com.wgh.rag.agent.entity.AgentPlan;
import com.wgh.rag.agent.entity.AgentStep;
import com.wgh.rag.agent.exception.AgentException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * HITL（Human-In-The-Loop）闸门 —— 暂停 / 恢复 / 确认。
 * hitl=true 步骤且窗口开启 HITL 时：持久化 HITL_PENDING + PAUSED 后线程退出，
 * 等待 hitl-confirm API 触发确认与恢复。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HitlGate {

    private final PlanLifecycleService planLifecycleService;
    private final AgentEventPublisher eventPublisher;

    /**
     * 是否应暂停：Planner 标记 hitl=true、窗口启用 HITL、且还没收到用户输入
     *（resultSummary 为空意味着还没收到用户输入）。
     */
    public boolean shouldPause(StepDTO stepDTO, boolean hitlEnabled, AgentStep dbStep) {
        return Boolean.TRUE.equals(stepDTO.getHitl()) && hitlEnabled
                && dbStep.getResultSummary() == null;
    }

    /** 暂停：先落库（step=HITL_PENDING, plan=PAUSED），再发 step_hitl_pending / plan_paused。 */
    public void pauseAndNotify(AgentPlan plan, AgentStep dbStep, StepDTO stepDTO,
                               BiConsumer<String, Map<String, Object>> sse) {
        planLifecycleService.markHitlPendingTx(plan, dbStep, stepDTO);
        eventPublisher.emit(sse, AgentEvents.STEP_HITL_PENDING, Map.of(
                "id", dbStep.getId(),
                "stepId", stepDTO.getStepId(),
                "stepIndex", dbStep.getStepIndex(),
                "stepTitle", stepDTO.getTitle(),
                "expectedInput", stepDTO.getExpectedInput(),
                "description", stepDTO.getDescription()));
        eventPublisher.emit(sse, AgentEvents.PLAN_PAUSED, Map.of("planId", plan.getId(), "reason", "等待用户确认"));
        // 调用方线程退出，等待用户 hitl-confirm API
    }

    /** 定位唯一 HITL_PENDING 步骤；没有则抛 AgentException。 */
    public AgentStep findHitlPendingStep(List<AgentStep> dbSteps) {
        return dbSteps.stream()
                .filter(s -> "HITL_PENDING".equals(s.getStatus()))
                .findFirst()
                .orElseThrow(() -> new AgentException("没有 HITL_PENDING 步骤"));
    }

    /**
     * 用户确认：把用户输入存进 resultSummary（AgentStep 没有 expectedInput 列，复用这个字段），
     * 步骤视为前置成功；plan 状态恢复 RUNNING。
     */
    public void confirmAndResumePlan(AgentPlan plan, AgentStep hitlStep, String userInput) {
        hitlStep.setResultSummary(userInput);
        hitlStep.setStatus("SUCCESS"); // 用户确认后视为前置成功，继续执行
        planLifecycleService.updateStep(hitlStep);

        plan.setStatus("RUNNING");
        plan.setUpdateTime(LocalDateTime.now());
        planLifecycleService.updatePlan(plan);
    }
}
