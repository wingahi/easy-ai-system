package com.wgh.rag.agent.service;

import com.wgh.rag.agent.constant.AgentConstants;

import com.wgh.rag.agent.StepExecutor;
import com.wgh.rag.agent.config.AgentProperties;
import com.wgh.rag.agent.constant.AgentEvents;
import com.wgh.rag.agent.dto.AgentStepResult;
import com.wgh.rag.agent.dto.PlanDTO;
import com.wgh.rag.agent.dto.PlanDTO.StepDTO;
import com.wgh.rag.agent.entity.AgentPlan;
import com.wgh.rag.agent.entity.AgentStep;
import com.wgh.rag.agent.exception.AgentException;
import com.wgh.rag.agent.planner.Planner;
import com.wgh.rag.agent.reflection.ReflectionEngine;
import com.wgh.rag.llm.entity.LlmModel;
import com.wgh.rag.tool.entity.ToolConfig;
import dev.langchain4j.agent.tool.ToolSpecification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.BiConsumer;

/**
 * Agent 编排器（Facade）—— Plan-and-Execute + Reflection + HITL 状态机 + 动态重规划。
 * 只负责流程编排，职责委托给协作类：
 * - {@link PlanLifecycleService}：plan/step 持久化 + 状态机迁移
 * - {@link StepScheduler}：拓扑排序 + 就绪步骤选取
 * - {@link HitlGate}：HITL 暂停/恢复/确认
 * - {@link AgentEventPublisher}：SSE 事件统一出口
 *
 * 线程模型：
 * - orchestrate() 首次进入：规划 → 持久化 → 拓扑排序 → 逐步执行（Reflection/HITL/重规划 全部同步）
 * - 遇 HITL 步骤：plan.status=PAUSED → 线程退出 → 等待用户 hitl-confirm API
 * - resume(planId)：从 DB 加载 plan+steps → 定位 HITL_PENDING 步骤 → 注入用户输入 → 继续后续步骤
 * - 遇失败且不可重试：触发 Planner.replan() 替换剩余 PENDING 步骤
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentOrchestrator {

    private final Planner planner;
    private final StepExecutor stepExecutor;
    private final ReflectionEngine reflectionEngine;
    private final PlanLifecycleService planLifecycleService;
    private final StepScheduler stepScheduler;
    private final HitlGate hitlGate;
    private final AgentEventPublisher eventPublisher;
    private final AgentProperties agentProperties;

    // ==================== 首次入口：规划 + 执行 ====================

    /**
     * 首次进入 Agent 编排。规划 + 全链路执行（含 Reflection / HITL / 重规划）。
     *
     * @return 最后一个 SUCCESS 步骤的 AgentStepResult；若遇 HITL_PENDING 则返回 null（线程已退出）
     */
    public AgentStepResult orchestrate(
            Long sessionId, Long windowId, Long windowModelId, Long plannerModelId,
            String userGoal, List<String> toolNames,
            List<ToolSpecification> toolSpecs, Map<String, ToolConfig> toolRegistry,
            boolean hasKb, List<Long> kbIds,
            double reflectionThreshold, int reflectionMaxRetries, boolean hitlEnabled,
            BiConsumer<String, Map<String, Object>> sse) {
        return orchestrate(sessionId, windowId, windowModelId, plannerModelId,
                userGoal, toolNames, toolSpecs, toolRegistry, hasKb, kbIds,
                reflectionThreshold, reflectionMaxRetries, hitlEnabled, sse, null, null);
    }

    /**
     * 首次进入 Agent 编排（显式传入故障转移链路）。
     *
     * @param executorChain 执行模型链路（按聊天窗口 route_group 解析，可为 null 表示按模型自身路由组）
     * @param plannerChain  Planner 模型链路（同上）
     */
    public AgentStepResult orchestrate(
            Long sessionId, Long windowId, Long windowModelId, Long plannerModelId,
            String userGoal, List<String> toolNames,
            List<ToolSpecification> toolSpecs, Map<String, ToolConfig> toolRegistry,
            boolean hasKb, List<Long> kbIds,
            double reflectionThreshold, int reflectionMaxRetries, boolean hitlEnabled,
            BiConsumer<String, Map<String, Object>> sse,
            List<LlmModel> executorChain, List<LlmModel> plannerChain) {

        int maxSteps = agentProperties.getMaxSteps();

        // 1. Planner 生成计划（非事务）
        log.info("AgentOrchestrator 开始规划: window={}, session={}", windowId, sessionId);
        PlanDTO planDTO;
        try {
            planDTO = plannerChain != null
                    ? planner.generate(plannerChain, plannerModelId, userGoal, toolNames, hasKb, maxSteps)
                    : planner.generate(plannerModelId, userGoal, toolNames, hasKb, maxSteps);
        } catch (AgentException e) {
            eventPublisher.emit(sse, AgentEvents.PLAN_CREATE_FAILED, Map.of("reason", e.getMessage()));
            throw e;
        }

        // 2. 持久化 plan + 所有 step
        AgentPlan plan = planLifecycleService.createPlanTx(sessionId, windowId, windowModelId, userGoal, planDTO);
        log.info("AgentPlan 已持久化: id={}, steps={}", plan.getId(), planDTO.getSteps().size());
        eventPublisher.emit(sse, AgentEvents.PLAN_CREATED, Map.of(
                "planId", plan.getId(),
                "planSummary", planDTO.getSummary(),
                "totalSteps", planDTO.getSteps().size()));

        // 3. 拓扑排序 + 持久化 steps
        List<StepDTO> orderedSteps = stepScheduler.topoSort(planDTO.getSteps());
        if (orderedSteps.isEmpty()) {
            planLifecycleService.markPlanComplete(plan, AgentConstants.STATUS_COMPLETED);
            eventPublisher.emit(sse, AgentEvents.PLAN_COMPLETE, Map.of("planId", plan.getId()));
            return AgentStepResult.builder().status(AgentConstants.STATUS_SUCCESS).finalText("").build();
        }
        planLifecycleService.persistSteps(orderedSteps, plan.getId());

        // 4. 逐步执行
        return executeSteps(plan, orderedSteps, windowModelId, toolSpecs, toolRegistry,
                hasKb, kbIds, userGoal, reflectionThreshold, reflectionMaxRetries, hitlEnabled, sse,
                new StringBuilder(), executorChain, plannerChain);
    }

    // ==================== 恢复入口：HITL 确认后继续 ====================

    /**
     * 从 HITL_PENDING 步骤恢复。
     * @param planId 已持久化的 plan ID
     * @param userInput 用户确认时填入的输入（可为空串）
     */
    public AgentStepResult resume(Long planId, String userInput,
                                   double reflectionThreshold, int reflectionMaxRetries,
                                   BiConsumer<String, Map<String, Object>> sse) {
        return resume(planId, userInput, reflectionThreshold, reflectionMaxRetries, sse, null, null);
    }

    /**
     * 从 HITL_PENDING 步骤恢复（显式传入故障转移链路）。
     * 恢复入口没有实时窗口上下文，由调用方按 plan.chatWindowId 解析窗口 route_group 后传入；
     * 传 null 时退回模型自身路由组（兼容既有调用）。
     */
    public AgentStepResult resume(Long planId, String userInput,
                                   double reflectionThreshold, int reflectionMaxRetries,
                                   BiConsumer<String, Map<String, Object>> sse,
                                   List<LlmModel> executorChain, List<LlmModel> plannerChain) {
        AgentPlan plan = planLifecycleService.findPlan(planId);
        if (plan == null) throw new AgentException("Plan 不存在: " + planId);
        if (!AgentConstants.STATUS_PAUSED.equals(plan.getStatus())) throw new AgentException("Plan 非 PAUSED 状态，无法 resume");

        // 加载所有 step，找到 HITL_PENDING 的步骤
        List<AgentStep> dbSteps = planLifecycleService.loadSteps(planId);
        AgentStep hitlStep = hitlGate.findHitlPendingStep(dbSteps);

        // 构建上下文：已完成步骤的 resultSummary 聚合
        StringBuilder contextBuilder = new StringBuilder();
        for (AgentStep s : dbSteps) {
            if (AgentConstants.STATUS_SUCCESS.equals(s.getStatus()) && s.getResultSummary() != null) {
                contextBuilder.append("【步骤 ").append(s.getStepId()).append(" - ")
                        .append(s.getTitle()).append("】\n").append(s.getResultSummary()).append("\n\n");
            }
        }

        // 用户确认：注入用户输入，恢复 plan 为 RUNNING
        hitlGate.confirmAndResumePlan(plan, hitlStep, userInput);
        contextBuilder.append("【HITL 用户确认】").append(hitlStep.getTitle())
                .append(" → 用户输入: ").append(userInput).append("\n\n");

        // 构建剩余步骤列表：HITL_PENDING 之后的 PENDING 步骤
        int hitlIndex = dbSteps.indexOf(hitlStep);
        List<AgentStep> remainingDbSteps = dbSteps.subList(hitlIndex + 1, dbSteps.size())
                .stream().filter(s -> AgentConstants.STATUS_PENDING.equals(s.getStatus())).toList();

        // 把 db step 转 StepDTO（从 plan_json 里读原来的完整 step 定义）
        PlanDTO fullPlan = planLifecycleService.parsePlanJsonOrThrow(plan);
        Map<String, StepDTO> stepDtoById = new HashMap<>();
        for (StepDTO s : fullPlan.getSteps()) stepDtoById.put(s.getStepId(), s);
        List<StepDTO> remainingStepDTOs = remainingDbSteps.stream()
                .map(s -> stepDtoById.get(s.getStepId()))
                .filter(Objects::nonNull).toList();

        eventPublisher.emit(sse, AgentEvents.PLAN_RESUMED, Map.of("planId", planId, "fromStepId", hitlStep.getStepId()));

        // 继续执行剩余步骤
        return executeSteps(plan, remainingStepDTOs, plan.getLlmModelId(),
                null, null, false, List.of(), plan.getUserGoal(),
                reflectionThreshold, reflectionMaxRetries, true, sse, contextBuilder, executorChain, plannerChain);
    }

    // ==================== 执行控制 API ====================

    /** 取消整个计划。 */
    public void cancel(Long planId, BiConsumer<String, Map<String, Object>> sse) {
        AgentPlan plan = planLifecycleService.findPlan(planId);
        if (plan == null) return;
        planLifecycleService.cancelPlanAndSkipUnfinishedSteps(plan);
        eventPublisher.emit(sse, AgentEvents.PLAN_CANCELLED, Map.of("planId", planId));
    }

    /** 重试单个 FAILED 步骤（仅当计划处于 PAUSED 状态时）。 */
    public AgentStepResult retryStep(Long planId, String stepId,
                                      double reflectionThreshold, int reflectionMaxRetries,
                                      BiConsumer<String, Map<String, Object>> sse) {
        return retryStep(planId, stepId, reflectionThreshold, reflectionMaxRetries, sse, null, null);
    }

    /**
     * 重试单个步骤（显式传入故障转移链路），链路解析口径与 resume 一致。
     */
    public AgentStepResult retryStep(Long planId, String stepId,
                                      double reflectionThreshold, int reflectionMaxRetries,
                                      BiConsumer<String, Map<String, Object>> sse,
                                      List<LlmModel> executorChain, List<LlmModel> plannerChain) {
        AgentPlan plan = planLifecycleService.findPlan(planId);
        AgentStep step = planLifecycleService.findStep(planId, stepId);
        if (plan == null || step == null) throw new AgentException("plan/step 不存在");

        // 恢复 plan 状态
        plan.setStatus(AgentConstants.STATUS_RUNNING);
        planLifecycleService.updatePlan(plan);

        // 重置 step
        step.setStatus(AgentConstants.STATUS_PENDING);
        step.setObservation(null);
        step.setResultSummary(null);
        step.setRetryCount(0);
        planLifecycleService.updateStep(step);

        // 构建 DTO 并执行
        PlanDTO fullPlan = planLifecycleService.parsePlanJsonOrThrow(plan);
        StepDTO dto = fullPlan.getSteps().stream()
                .filter(s -> s.getStepId().equals(stepId)).findFirst().orElseThrow();

        // 构建上下文
        StringBuilder ctx = new StringBuilder();
        List<AgentStep> prevSteps = planLifecycleService.loadSucceededStepsBefore(planId, step.getStepIndex());
        for (AgentStep ps : prevSteps) {
            ctx.append("【步骤 ").append(ps.getStepId()).append("】\n").append(ps.getResultSummary()).append("\n\n");
        }

        return executeSteps(plan, List.of(dto), plan.getLlmModelId(),
                null, null, false, List.of(), plan.getUserGoal(),
                reflectionThreshold, reflectionMaxRetries, true, sse, ctx, executorChain, plannerChain);
    }

    /** 跳过单个 FAILED / PENDING 步骤。 */
    public void skipStep(Long planId, String stepId, BiConsumer<String, Map<String, Object>> sse) {
        AgentStep step = planLifecycleService.findStep(planId, stepId);
        if (step == null) return;
        step.setStatus(AgentConstants.STATUS_SKIPPED);
        planLifecycleService.updateStep(step);
        // 继续后续步骤：调用 resume-like 逻辑
        eventPublisher.emit(sse, AgentEvents.STEP_SKIPPED, Map.of("stepId", stepId, "reason", "manual skip"));
    }

    // ==================== 核心：逐步骤执行循环 ====================

    /**
     * 执行一组 StepDTO（拓扑排序后或 resume 时的剩余步骤）。
     * 每步独立小事务。含 Reflection 循环、HITL 检测、失败触发 re-plan。
     */
    private AgentStepResult executeSteps(AgentPlan plan, List<StepDTO> stepDTOs,
                                          Long executorModelId,
                                          List<ToolSpecification> toolSpecs, Map<String, ToolConfig> toolRegistry,
                                          boolean hasKb, List<Long> kbIds, String userGoal,
                                          double reflectionThreshold, int reflectionMaxRetries,
                                          boolean hitlEnabled,
                                          BiConsumer<String, Map<String, Object>> sse) {
        return executeSteps(plan, stepDTOs, executorModelId, toolSpecs, toolRegistry,
                hasKb, kbIds, userGoal, reflectionThreshold, reflectionMaxRetries, hitlEnabled, sse,
                new StringBuilder(), null, null);
    }

    private AgentStepResult executeSteps(AgentPlan plan, List<StepDTO> stepDTOs,
                                          Long executorModelId,
                                          List<ToolSpecification> toolSpecs, Map<String, ToolConfig> toolRegistry,
                                          boolean hasKb, List<Long> kbIds, String userGoal,
                                          double reflectionThreshold, int reflectionMaxRetries,
                                          boolean hitlEnabled,
                                          BiConsumer<String, Map<String, Object>> sse,
                                          StringBuilder contextBuilder,
                                          List<LlmModel> executorChain,
                                          List<LlmModel> plannerChain) {
        AgentStepResult lastResult = null;

        for (StepDTO stepDTO : stepDTOs) {
            if (AgentConstants.STATUS_CANCELLED.equals(plan.getStatus())) {
                log.info("Plan 已取消，停止后续步骤");
                break;
            }

            // 找 DB 里的对应 AgentStep
            AgentStep dbStep = planLifecycleService.findStep(plan.getId(), stepDTO.getStepId());
            if (dbStep == null) continue;
            if (AgentConstants.STATUS_SKIPPED.equals(dbStep.getStatus()) || AgentConstants.STATUS_SUCCESS.equals(dbStep.getStatus())) {
                continue; // 已完成或已跳过（resume 时可能存在）
            }

            // 如果 Planner 标记了 hitl=true 且窗口启用了 HITL，且我们还没有用户输入 → 暂停
            if (hitlGate.shouldPause(stepDTO, hitlEnabled, dbStep)) {
                hitlGate.pauseAndNotify(plan, dbStep, stepDTO, sse);
                return null;
            }

            // 5a. 更新状态 RUNNING
            planLifecycleService.markStepRunningTx(plan, dbStep);
            eventPublisher.emit(sse, AgentEvents.STEP_START, Map.of(
                    "stepId", stepDTO.getStepId(),
                    "stepTitle", stepDTO.getTitle(),
                    "stepIndex", dbStep.getStepIndex()));

            // 5b. 构建 step prompt
            String stepContext = buildStepContext(contextBuilder);
            String stepPrompt = buildStepPrompt(stepDTO, stepContext, userGoal);

            // 5c. 过滤工具
            List<ToolSpecification> stepToolSpecs = filterStepTools(toolSpecs, stepDTO.getRequiresTools());
            Map<String, ToolConfig> stepRegistry = filterStepRegistry(toolRegistry, stepDTO.getRequiresTools());

            // 5d. StepExecutor 执行（循环：执行 + Reflection 评估 + 重试）
            AgentStepResult result = null;
            long stepLatencyMs = 0;
            int retries = 0;
            while (retries <= Math.max(0, reflectionMaxRetries)) {
                if (retries > 0) {
                    log.info("Reflection 重试 第 {} 次 stepId={}", retries, stepDTO.getStepId());
                    eventPublisher.emit(sse, AgentEvents.STEP_RETRY, Map.of("stepId", stepDTO.getStepId(), "retryCount", retries));
                    planLifecycleService.updateRetryCountTx(dbStep, retries);
                }

                long stepStartMs = System.currentTimeMillis();
                Long effectiveExecutorModelId = executorModelId != null ? executorModelId : plan.getLlmModelId();
                result = executorChain != null
                        ? stepExecutor.executeWithChain(executorChain,
                                stepPrompt, stepToolSpecs, stepRegistry, plan.getLlmModelId(),
                                agentProperties.getMaxToolRoundsPerStep(),
                                Boolean.TRUE.equals(stepDTO.getRequiresRAG()),
                                stepDTO.getDescription() != null ? stepDTO.getDescription() : userGoal,
                                kbIds)
                        : stepExecutor.execute(effectiveExecutorModelId,
                                stepPrompt, stepToolSpecs, stepRegistry, plan.getLlmModelId(),
                                agentProperties.getMaxToolRoundsPerStep(),
                                Boolean.TRUE.equals(stepDTO.getRequiresRAG()),
                                stepDTO.getDescription() != null ? stepDTO.getDescription() : userGoal,
                                kbIds);
                stepLatencyMs = System.currentTimeMillis() - stepStartMs;
                emitModelSwitches(result, sse);

                // Reflection 评估（仅对 SUCCESS 状态的结果做质量检查）
                if (AgentConstants.STATUS_SUCCESS.equals(result.getStatus()) && reflectionEngine != null) {
                    // 优先用窗口配置的 executorModelId，否则 fallback 到 plan 的 llmModelId（Planner 用的模型）
                    Long criticModelId = effectiveExecutorModelId;
                    var reflection = executorChain != null
                            ? reflectionEngine.evaluate(
                                    stepDTO.getTitle() + ": " + stepDTO.getDescription(),
                                    result.getFinalText(), criticModelId, executorChain)
                            : reflectionEngine.evaluate(
                                    stepDTO.getTitle() + ": " + stepDTO.getDescription(),
                                    result.getFinalText(), criticModelId);
                    double score = reflection.getKey();
                    String feedback = reflection.getValue();
                    log.info("Reflection: stepId={}, score={}, feedback={}", stepDTO.getStepId(), score, feedback);

                    eventPublisher.emit(sse, AgentEvents.STEP_REFLECTION, Map.of(
                            "stepId", stepDTO.getStepId(),
                            "score", score, "feedback", feedback,
                            "retryCount", retries,
                            "pass", score >= reflectionThreshold));

                    if (score < reflectionThreshold) {
                        // 质量不过关 → 重试（把 feedback 注入 prompt 让下一轮改进）
                        retries++;
                        stepPrompt = stepPrompt + "\n\n## Reflection 反馈（请改进）\n" + feedback;
                        continue;
                    }
                    // 过关 → 持久化 reflection 分数
                    planLifecycleService.saveReflectionScoreTx(dbStep, score, feedback);
                }
                break; // 正常跳出 while
            }

            // 5e. 持久化结果
            String finalStatus = result.getStatus();
            String finalResultSummary = result.getResultSummary();
            long finalLatencyMs = stepLatencyMs;
            planLifecycleService.persistStepResultTx(plan, dbStep, stepDTO, result);

            // 5f. 发 SSE 事件 + 状态处理
            if (AgentConstants.STATUS_SUCCESS.equals(finalStatus)) {
                eventPublisher.emit(sse, AgentEvents.STEP_SUCCESS, Map.of(
                        "stepId", stepDTO.getStepId(),
                        "stepTitle", stepDTO.getTitle(),
                        "latencyMs", finalLatencyMs,
                        "toolCount", result.getToolInvocations() != null ? result.getToolInvocations().size() : 0,
                        "ragSourceCount", result.getRagSources() != null ? result.getRagSources().size() : 0));
                // 聚合 context 供下游
                if (finalResultSummary != null && !finalResultSummary.isBlank()) {
                    contextBuilder.append("【步骤 ").append(stepDTO.getStepId()).append(" - ")
                            .append(stepDTO.getTitle()).append("】\n")
                            .append(finalResultSummary).append("\n\n");
                }
            } else if (AgentConstants.STATUS_HITL_PENDING.equals(finalStatus)) {
                eventPublisher.emit(sse, AgentEvents.PLAN_PAUSED, Map.of("planId", plan.getId(), "reason", "步骤内 HITL"));
                return null;
            } else {
                // FAILED → 触发动态重规划
                log.warn("步骤 FAILED: stepId={}, error={}", stepDTO.getStepId(), result.getErrorMessage());
                eventPublisher.emit(sse, AgentEvents.STEP_FAILED, Map.of(
                        "stepId", stepDTO.getStepId(),
                        "stepTitle", stepDTO.getTitle(),
                        "error", result.getErrorMessage(),
                        "retryCount", retries));

                // 调用 Planner.replan() 重新生成剩余 PENDING 步骤
                List<StepDTO> pendingRemaining = planLifecycleService.collectPendingRemaining(plan.getId());
                if (!pendingRemaining.isEmpty()) {
                    try {
                        String finishedSummary = contextBuilder.toString();
                        String failureHint = "步骤 " + stepDTO.getStepId() + "（" + stepDTO.getTitle() + "）失败: "
                                + result.getErrorMessage() + "。请重新规划剩余任务。";
                        PlanDTO replanned = plannerChain != null
                                ? planner.replan(plannerChain, plan.getLlmModelId(), userGoal,
                                        planLifecycleService.parsePlanJsonOrEmpty(plan),
                                        toolSpecs.stream().map(ToolSpecification::name).toList(),
                                        hasKb, agentProperties.getMaxSteps(),
                                        finishedSummary, failureHint)
                                : planner.replan(
                                        plan.getLlmModelId(), userGoal,
                                        planLifecycleService.parsePlanJsonOrEmpty(plan),
                                        toolSpecs.stream().map(ToolSpecification::name).toList(),
                                        hasKb, agentProperties.getMaxSteps(),
                                        finishedSummary, failureHint);

                        // 持久化新的 step
                        planLifecycleService.insertReplannedSteps(plan.getId(), replanned.getSteps(), dbStep.getStepIndex() + 1);

                        // 更新 plan_json（追加 replanned 的 steps）
                        planLifecycleService.mergeStepsAndSaveTx(plan, replanned);

                        eventPublisher.emit(sse, AgentEvents.PLAN_UPDATED, Map.of(
                                "planId", plan.getId(),
                                "addedStepCount", replanned.getSteps().size(),
                                "reason", "step_failed_replan"));

                        // 替换剩余步骤继续执行
                        stepDTOs = new ArrayList<>(replanned.getSteps());
                        continue; // 跳过 for 循环的 i++，从新步骤开始
                    } catch (Exception replanEx) {
                        log.error("动态重规划失败", replanEx);
                        // 重规划也失败 → 终止整个计划
                        planLifecycleService.failPlanTx(plan);
                        eventPublisher.emit(sse, AgentEvents.PLAN_FAILED, Map.of(
                                "planId", plan.getId(),
                                "reason", "step_failed_and_replan_failed",
                                "failedStepId", stepDTO.getStepId()));
                        return null;
                    }
                } else {
                    // 无剩余待执行步骤 → 终止计划
                    planLifecycleService.failPlanTx(plan);
                    eventPublisher.emit(sse, AgentEvents.PLAN_FAILED, Map.of(
                            "planId", plan.getId(), "reason", "no_pending_after_failed"));
                    return null;
                }
            }
            lastResult = result;
        }

        // 6. Plan 完成
        if (AgentConstants.STATUS_RUNNING.equals(plan.getStatus())) {
            planLifecycleService.markPlanCompleteTx(plan, AgentConstants.STATUS_COMPLETED);
            eventPublisher.emit(sse, AgentEvents.PLAN_COMPLETE, Map.of(
                    "planId", plan.getId(),
                    "totalSteps", plan.getMaxSteps()));
        }

        return lastResult;
    }

    // ==================== prompt 构建 + 工具过滤 ====================

    /** 把步骤内的模型切换（故障转移）下发为 model_switch 事件，与普通 RAG 口径一致 */
    private void emitModelSwitches(AgentStepResult result, BiConsumer<String, Map<String, Object>> sse) {
        if (result == null || result.getModelSwitches() == null) return;
        for (AgentStepResult.ModelSwitchRecord sw : result.getModelSwitches()) {
            eventPublisher.emit(sse, AgentEvents.MODEL_SWITCH, Map.of(
                    "modelName", sw.getModelName() != null ? sw.getModelName() : "",
                    "modelId", sw.getModelId() != null ? sw.getModelId() : 0L,
                    "reason", sw.getReason() != null ? sw.getReason() : ""));
        }
    }

    private String buildStepContext(StringBuilder contextBuilder) {
        String ctx = contextBuilder.toString();
        return ctx.isBlank() ? "" : """
                ## 前序步骤执行结果（供你参考，在此基础上继续）
                %s
                """.formatted(ctx);
    }

    private String buildStepPrompt(StepDTO stepDTO, String stepContext, String userGoal) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 用户原始目标\n").append(userGoal).append("\n\n");
        sb.append(stepContext).append("\n");
        sb.append("## 当前任务步骤\n");
        sb.append("- 步骤ID: ").append(stepDTO.getStepId()).append("\n");
        sb.append("- 标题: ").append(stepDTO.getTitle()).append("\n");
        sb.append("- 详细说明: ").append(stepDTO.getDescription()).append("\n");
        if (stepDTO.getExpectedInput() != null && !stepDTO.getExpectedInput().isBlank()) {
            sb.append("- 期望输入: ").append(stepDTO.getExpectedInput()).append("\n");
        }
        if (stepDTO.getOutputType() != null) {
            sb.append("- 输出类型: ").append(stepDTO.getOutputType()).append("\n");
        }
        sb.append("\n请执行此步骤。如果需要调用工具，请直接发起 tool call。\n");
        return sb.toString();
    }

    private List<ToolSpecification> filterStepTools(List<ToolSpecification> all, List<String> requiresTools) {
        if (all == null || all.isEmpty()) return List.of();
        if (requiresTools == null || requiresTools.isEmpty()) return all;
        Set<String> wanted = new HashSet<>(requiresTools);
        return all.stream().filter(s -> wanted.contains(s.name())).toList();
    }

    private Map<String, ToolConfig> filterStepRegistry(Map<String, ToolConfig> all, List<String> requiresTools) {
        if (all == null || all.isEmpty()) return Map.of();
        if (requiresTools == null || requiresTools.isEmpty()) return all;
        Set<String> wanted = new HashSet<>(requiresTools);
        Map<String, ToolConfig> filtered = new HashMap<>();
        for (Map.Entry<String, ToolConfig> e : all.entrySet()) {
            if (wanted.contains(e.getKey())) filtered.put(e.getKey(), e.getValue());
        }
        return filtered;
    }
}
