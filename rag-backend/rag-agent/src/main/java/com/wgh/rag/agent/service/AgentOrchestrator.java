package com.wgh.rag.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.agent.StepExecutor;
import com.wgh.rag.agent.config.AgentProperties;
import com.wgh.rag.agent.dto.AgentStepResult;
import com.wgh.rag.agent.dto.PlanDTO;
import com.wgh.rag.agent.dto.PlanDTO.StepDTO;
import com.wgh.rag.agent.entity.AgentPlan;
import com.wgh.rag.agent.entity.AgentStep;
import com.wgh.rag.agent.exception.AgentException;
import com.wgh.rag.agent.mapper.AgentPlanMapper;
import com.wgh.rag.agent.mapper.AgentStepMapper;
import com.wgh.rag.agent.planner.Planner;
import com.wgh.rag.agent.reflection.ReflectionEngine;
import com.wgh.rag.tool.entity.ToolConfig;
import dev.langchain4j.agent.tool.ToolSpecification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/**
 * Agent 编排器 —— Plan-and-Execute + Reflection + HITL 状态机 + 动态重规划。
 *
 * 线程模型：
 * - orchestrate() 首次进入：规划 → 持久化 → 拓扑排序 → 逐步执行（Reflection/HITL/重规划 全部同步）
 * - 遇 HITL 步骤：plan.status=PAUSED → 线程退出 → 等待用户 hitl-confirm API
 * - resume(planId)：从 DB 加载 plan+steps → 定位 HITL_PENDING 步骤 → 注入用户输入 → 继续后续步骤
 * - 遇失败且不可重试：触发 Planner.replan() 替换剩余 PENDING 步骤
 *
 * 事务策略：
 * - 不用外层 @Transactional。每个步骤用 TransactionTemplate 开独立小事务。
 * - Planner 规划 + plan 持久化：单独事务
 * - 每个 step 的执行结果持久化：单独事务（失败时只回滚该步，不影响已完成步骤）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentOrchestrator {

    private final Planner planner;
    private final StepExecutor stepExecutor;
    private final ReflectionEngine reflectionEngine;
    private final AgentPlanMapper planMapper;
    private final AgentStepMapper stepMapper;
    private final AgentProperties agentProperties;
    private final ObjectMapper objectMapper;
    private final PlatformTransactionManager transactionManager;

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

        int maxSteps = agentProperties.getMaxSteps();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        // 1. Planner 生成计划（非事务）
        log.info("AgentOrchestrator 开始规划: window={}, session={}", windowId, sessionId);
        PlanDTO planDTO;
        try {
            planDTO = planner.generate(plannerModelId, userGoal, toolNames, hasKb, maxSteps);
        } catch (AgentException e) {
            emit(sse, "plan_create_failed", Map.of("reason", e.getMessage()));
            throw e;
        }

        // 2. 持久化 plan + 所有 step（小事务）
        AgentPlan plan = tx.execute(status -> {
            AgentPlan p = new AgentPlan();
            p.setSessionId(sessionId);
            p.setChatWindowId(windowId);
            p.setLlmModelId(windowModelId);
            p.setStatus("RUNNING");
            p.setUserGoal(userGoal);
            p.setPlanJson(toJson(planDTO));
            p.setCurrentStepIndex(0);
            p.setMaxSteps(planDTO.getSteps().size());
            p.setTotalTokens(0);
            planMapper.insert(p);
            return p;
        });
        log.info("AgentPlan 已持久化: id={}, steps={}", plan.getId(), planDTO.getSteps().size());
        emit(sse, "plan_created", Map.of(
                "planId", plan.getId(),
                "planSummary", planDTO.getSummary(),
                "totalSteps", planDTO.getSteps().size()));

        // 3. 拓扑排序 + 持久化 steps（小事务）
        List<StepDTO> orderedSteps = topoSort(planDTO.getSteps());
        if (orderedSteps.isEmpty()) {
            markPlanComplete(plan, "COMPLETED");
            emit(sse, "plan_complete", Map.of("planId", plan.getId()));
            return AgentStepResult.builder().status("SUCCESS").finalText("").build();
        }
        persistSteps(orderedSteps, plan.getId());

        // 4. 逐步执行（每步独立小事务）
        return executeSteps(plan, orderedSteps, windowModelId, toolSpecs, toolRegistry,
                hasKb, kbIds, userGoal, reflectionThreshold, reflectionMaxRetries, hitlEnabled, sse);
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
        AgentPlan plan = planMapper.selectById(planId);
        if (plan == null) throw new AgentException("Plan 不存在: " + planId);
        if (!"PAUSED".equals(plan.getStatus())) throw new AgentException("Plan 非 PAUSED 状态，无法 resume");

        // 加载所有 step
        List<AgentStep> dbSteps = stepMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AgentStep>()
                        .eq(AgentStep::getPlanId, planId)
                        .eq(AgentStep::getDeleted, 0)
                        .orderByAsc(AgentStep::getStepIndex));

        // 找到 HITL_PENDING 的步骤
        AgentStep hitlStep = dbSteps.stream()
                .filter(s -> "HITL_PENDING".equals(s.getStatus()))
                .findFirst()
                .orElseThrow(() -> new AgentException("没有 HITL_PENDING 步骤"));

        // 构建上下文：已完成步骤的 resultSummary 聚合
        StringBuilder contextBuilder = new StringBuilder();
        for (AgentStep s : dbSteps) {
            if ("SUCCESS".equals(s.getStatus()) && s.getResultSummary() != null) {
                contextBuilder.append("【步骤 ").append(s.getStepId()).append(" - ")
                        .append(s.getTitle()).append("】\n").append(s.getResultSummary()).append("\n\n");
            }
        }

        // 把用户输入存进 resultSummary（AgentStep 没有 expectedInput 列，复用这个字段）
        hitlStep.setResultSummary(userInput);
        hitlStep.setStatus("SUCCESS"); // 用户确认后视为前置成功，继续执行
        stepMapper.updateById(hitlStep);
        contextBuilder.append("【HITL 用户确认】").append(hitlStep.getTitle())
                .append(" → 用户输入: ").append(userInput).append("\n\n");

        // 更新 plan 状态为 RUNNING
        plan.setStatus("RUNNING");
        plan.setUpdateTime(LocalDateTime.now());
        planMapper.updateById(plan);

        // 构建剩余步骤列表：HITL_PENDING 之后的 PENDING 步骤
        int hitlIndex = dbSteps.indexOf(hitlStep);
        List<AgentStep> remainingDbSteps = dbSteps.subList(hitlIndex + 1, dbSteps.size())
                .stream().filter(s -> "PENDING".equals(s.getStatus())).toList();

        // 把 db step 转 StepDTO（从 plan_json 里读原来的完整 step 定义）
        PlanDTO fullPlan;
        try {
            fullPlan = objectMapper.readValue(plan.getPlanJson(), new TypeReference<>() {});
        } catch (Exception e) {
            throw new AgentException("Plan JSON 解析失败", e);
        }
        Map<String, StepDTO> stepDtoById = new HashMap<>();
        for (StepDTO s : fullPlan.getSteps()) stepDtoById.put(s.getStepId(), s);
        List<StepDTO> remainingStepDTOs = remainingDbSteps.stream()
                .map(s -> stepDtoById.get(s.getStepId()))
                .filter(Objects::nonNull).toList();

        emit(sse, "plan_resumed", Map.of("planId", planId, "fromStepId", hitlStep.getStepId()));

        // 继续执行剩余步骤
        return executeSteps(plan, remainingStepDTOs, plan.getLlmModelId(),
                null, null, false, List.of(), plan.getUserGoal(),
                reflectionThreshold, reflectionMaxRetries, true, sse, contextBuilder);
    }

    // ==================== 执行控制 API ====================

    /** 取消整个计划。 */
    public void cancel(Long planId, BiConsumer<String, Map<String, Object>> sse) {
        AgentPlan plan = planMapper.selectById(planId);
        if (plan == null) return;
        plan.setStatus("CANCELLED");
        plan.setFinishTime(LocalDateTime.now());
        planMapper.updateById(plan);
        // 把所有 RUNNING / PENDING / HITL_PENDING 步骤标记 SKIPPED
        stepMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AgentStep>()
                .eq(AgentStep::getPlanId, planId)
                .in(AgentStep::getStatus, "RUNNING", "PENDING", "HITL_PENDING")
                .set(AgentStep::getStatus, "SKIPPED"));
        emit(sse, "plan_cancelled", Map.of("planId", planId));
    }

    /** 重试单个 FAILED 步骤（仅当计划处于 PAUSED 状态时）。 */
    public AgentStepResult retryStep(Long planId, String stepId,
                                      double reflectionThreshold, int reflectionMaxRetries,
                                      BiConsumer<String, Map<String, Object>> sse) {
        AgentPlan plan = planMapper.selectById(planId);
        AgentStep step = stepMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AgentStep>()
                        .eq(AgentStep::getPlanId, planId)
                        .eq(AgentStep::getStepId, stepId)
                        .last("LIMIT 1"));
        if (plan == null || step == null) throw new AgentException("plan/step 不存在");

        // 恢复 plan 状态
        plan.setStatus("RUNNING");
        planMapper.updateById(plan);

        // 重置 step
        step.setStatus("PENDING");
        step.setObservation(null);
        step.setResultSummary(null);
        step.setRetryCount(0);
        stepMapper.updateById(step);

        // 构建 DTO 并执行
        PlanDTO fullPlan;
        try { fullPlan = objectMapper.readValue(plan.getPlanJson(), new TypeReference<>() {}); }
        catch (Exception e) { throw new AgentException("Plan JSON 解析失败", e); }
        StepDTO dto = fullPlan.getSteps().stream()
                .filter(s -> s.getStepId().equals(stepId)).findFirst().orElseThrow();

        // 构建上下文
        StringBuilder ctx = new StringBuilder();
        List<AgentStep> prevSteps = stepMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AgentStep>()
                        .eq(AgentStep::getPlanId, planId)
                        .lt(AgentStep::getStepIndex, step.getStepIndex())
                        .eq(AgentStep::getStatus, "SUCCESS")
                        .orderByAsc(AgentStep::getStepIndex));
        for (AgentStep ps : prevSteps) {
            ctx.append("【步骤 ").append(ps.getStepId()).append("】\n").append(ps.getResultSummary()).append("\n\n");
        }

        return executeSteps(plan, List.of(dto), plan.getLlmModelId(),
                null, null, false, List.of(), plan.getUserGoal(),
                reflectionThreshold, reflectionMaxRetries, true, sse, ctx);
    }

    /** 跳过单个 FAILED / PENDING 步骤。 */
    public void skipStep(Long planId, String stepId, BiConsumer<String, Map<String, Object>> sse) {
        AgentStep step = stepMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AgentStep>()
                        .eq(AgentStep::getPlanId, planId)
                        .eq(AgentStep::getStepId, stepId)
                        .last("LIMIT 1"));
        if (step == null) return;
        step.setStatus("SKIPPED");
        stepMapper.updateById(step);
        // 继续后续步骤：调用 resume-like 逻辑
        emit(sse, "step_skipped", Map.of("stepId", stepId, "reason", "manual skip"));
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
                new StringBuilder());
    }

    private AgentStepResult executeSteps(AgentPlan plan, List<StepDTO> stepDTOs,
                                          Long executorModelId,
                                          List<ToolSpecification> toolSpecs, Map<String, ToolConfig> toolRegistry,
                                          boolean hasKb, List<Long> kbIds, String userGoal,
                                          double reflectionThreshold, int reflectionMaxRetries,
                                          boolean hitlEnabled,
                                          BiConsumer<String, Map<String, Object>> sse,
                                          StringBuilder contextBuilder) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        AgentStepResult lastResult = null;

        for (StepDTO stepDTO : stepDTOs) {
            if ("CANCELLED".equals(plan.getStatus())) {
                log.info("Plan 已取消，停止后续步骤");
                break;
            }

            // 找 DB 里的对应 AgentStep
            AgentStep dbStep = stepMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AgentStep>()
                            .eq(AgentStep::getPlanId, plan.getId())
                            .eq(AgentStep::getStepId, stepDTO.getStepId())
                            .last("LIMIT 1"));
            if (dbStep == null) continue;
            if ("SKIPPED".equals(dbStep.getStatus()) || "SUCCESS".equals(dbStep.getStatus())) {
                continue; // 已完成或已跳过（resume 时可能存在）
            }

            // 如果 Planner 标记了 hitl=true 且窗口启用了 HITL，且我们还没有用户输入 → 暂停
            if (Boolean.TRUE.equals(stepDTO.getHitl()) && hitlEnabled
                    && dbStep.getResultSummary() == null) { // resultSummary 为空意味着还没收到用户输入
                // 把步骤状态持久化为 HITL_PENDING + Plan 状态 PAUSED
                tx.executeWithoutResult(status -> {
                    dbStep.setStatus("HITL_PENDING");
                    dbStep.setThought("HITL 节点等待用户确认: " + stepDTO.getDescription());
                    stepMapper.updateById(dbStep);
                    plan.setStatus("PAUSED");
                    planMapper.updateById(plan);
                });
                emit(sse, "step_hitl_pending", Map.of(
                        "id", dbStep.getId(),
                        "stepId", stepDTO.getStepId(),
                        "stepIndex", dbStep.getStepIndex(),
                        "stepTitle", stepDTO.getTitle(),
                        "expectedInput", stepDTO.getExpectedInput(),
                        "description", stepDTO.getDescription()));
                emit(sse, "plan_paused", Map.of("planId", plan.getId(), "reason", "等待用户确认"));
                // 线程退出，等待用户 hitl-confirm API
                return null;
            }

            // 5a. 更新状态 RUNNING（小事务）
            tx.executeWithoutResult(status -> {
                dbStep.setStatus("RUNNING");
                dbStep.setStartTime(LocalDateTime.now());
                stepMapper.updateById(dbStep);
                plan.setCurrentStepIndex(dbStep.getStepIndex());
                planMapper.updateById(plan);
            });
            emit(sse, "step_start", Map.of(
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
                    emit(sse, "step_retry", Map.of("stepId", stepDTO.getStepId(), "retryCount", retries));
                    final int currentRetries = retries;
                    tx.executeWithoutResult(status -> {
                        dbStep.setRetryCount(currentRetries);
                        stepMapper.updateById(dbStep);
                    });
                }

                long stepStartMs = System.currentTimeMillis();
                result = stepExecutor.execute(
                        executorModelId != null ? executorModelId : plan.getLlmModelId(),
                        stepPrompt, stepToolSpecs, stepRegistry, plan.getLlmModelId(),
                        agentProperties.getMaxToolRoundsPerStep(),
                        Boolean.TRUE.equals(stepDTO.getRequiresRAG()),
                        stepDTO.getDescription() != null ? stepDTO.getDescription() : userGoal,
                        kbIds);
                stepLatencyMs = System.currentTimeMillis() - stepStartMs;

                // Reflection 评估（仅对 SUCCESS 状态的结果做质量检查）
                if ("SUCCESS".equals(result.getStatus()) && reflectionEngine != null) {
                    // 优先用窗口配置的 executorModelId，否则 fallback 到 plan 的 llmModelId（Planner 用的模型）
                    Long criticModelId = executorModelId != null ? executorModelId : plan.getLlmModelId();
                    var reflection = reflectionEngine.evaluate(
                            stepDTO.getTitle() + ": " + stepDTO.getDescription(),
                            result.getFinalText(), criticModelId);
                    double score = reflection.getKey();
                    String feedback = reflection.getValue();
                    log.info("Reflection: stepId={}, score={}, feedback={}", stepDTO.getStepId(), score, feedback);

                    emit(sse, "step_reflection", Map.of(
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
                    tx.executeWithoutResult(status -> {
                        dbStep.setReflectionScore(score);
                        dbStep.setReflectionFeedback(feedback);
                        stepMapper.updateById(dbStep);
                    });
                }
                break; // 正常跳出 while
            }

            // 5e. 持久化结果（小事务）
            String finalStatus = result.getStatus();
            String finalResultSummary = result.getResultSummary();
            long finalLatencyMs = stepLatencyMs;
            int finalTokenCount = result.getTokenCount();
            List<String> finalThoughts = result.getThoughts();
            List<AgentStepResult.ToolInvocationRecord> finalToolInvocations = result.getToolInvocations();
            List<AgentStepResult.RagSourceRecord> finalRagSources = result.getRagSources();
            String finalErrorMessage = result.getErrorMessage();

            tx.executeWithoutResult(status -> {
                dbStep.setThought(finalThoughts != null && !finalThoughts.isEmpty()
                        ? String.join("\n", finalThoughts) : null);
                dbStep.setObservation(toJson(finalToolInvocations));
                dbStep.setResultSummary(finalResultSummary);
                dbStep.setEndTime(LocalDateTime.now());
                dbStep.setTokenCount(finalTokenCount);
                dbStep.setActionTool(stepDTO.getRequiresTools() != null
                        ? String.join(",", stepDTO.getRequiresTools()) : null);

                switch (finalStatus) {
                    case "SUCCESS" -> dbStep.setStatus("SUCCESS");
                    case "FAILED" -> dbStep.setStatus("FAILED");
                    case "HITL_PENDING" -> {
                        dbStep.setStatus("HITL_PENDING");
                        plan.setStatus("PAUSED");
                    }
                    default -> dbStep.setStatus("FAILED");
                }
                stepMapper.updateById(dbStep);
                planMapper.updateById(plan);
            });

            // 5f. 发 SSE 事件 + 状态处理
            if ("SUCCESS".equals(finalStatus)) {
                emit(sse, "step_success", Map.of(
                        "stepId", stepDTO.getStepId(),
                        "stepTitle", stepDTO.getTitle(),
                        "latencyMs", finalLatencyMs,
                        "toolCount", finalToolInvocations != null ? finalToolInvocations.size() : 0,
                        "ragSourceCount", finalRagSources != null ? finalRagSources.size() : 0));
                // 聚合 context 供下游
                if (finalResultSummary != null && !finalResultSummary.isBlank()) {
                    contextBuilder.append("【步骤 ").append(stepDTO.getStepId()).append(" - ")
                            .append(stepDTO.getTitle()).append("】\n")
                            .append(finalResultSummary).append("\n\n");
                }
            } else if ("HITL_PENDING".equals(finalStatus)) {
                emit(sse, "plan_paused", Map.of("planId", plan.getId(), "reason", "步骤内 HITL"));
                return null;
            } else {
                // FAILED → 触发动态重规划
                log.warn("步骤 FAILED: stepId={}, error={}", stepDTO.getStepId(), finalErrorMessage);
                emit(sse, "step_failed", Map.of(
                        "stepId", stepDTO.getStepId(),
                        "stepTitle", stepDTO.getTitle(),
                        "error", finalErrorMessage,
                        "retryCount", retries));

                // 调用 Planner.replan() 重新生成剩余 PENDING 步骤
                List<StepDTO> pendingRemaining = collectPendingRemaining(plan.getId(), stepDTO.getStepId());
                if (!pendingRemaining.isEmpty()) {
                    try {
                        String finishedSummary = contextBuilder.toString();
                        String failureHint = "步骤 " + stepDTO.getStepId() + "（" + stepDTO.getTitle() + "）失败: "
                                + finalErrorMessage + "。请重新规划剩余任务。";
                        PlanDTO replanned = planner.replan(
                                plan.getLlmModelId(), userGoal,
                                reconstructOriginalPlan(plan),
                                toolSpecs.stream().map(ToolSpecification::name).toList(),
                                hasKb, agentProperties.getMaxSteps(),
                                finishedSummary, failureHint);

                        // 持久化新的 step
                        int newStepIndex = dbStep.getStepIndex() + 1;
                        for (StepDTO newStep : replanned.getSteps()) {
                            AgentStep newDbStep = new AgentStep();
                            newDbStep.setPlanId(plan.getId());
                            newDbStep.setStepIndex(newStepIndex++);
                            newDbStep.setStepId(newStep.getStepId());
                            newDbStep.setTitle(newStep.getTitle());
                            newDbStep.setDescription(newStep.getDescription());
                            newDbStep.setDependsOn(newStep.getDependsOn() != null ? String.join(",", newStep.getDependsOn()) : null);
                            newDbStep.setStatus("PENDING");
                            newDbStep.setMaxRetries(agentProperties.getDefaultReflectionRetries());
                            stepMapper.insert(newDbStep);
                        }

                        // 更新 plan_json（追加 replanned 的 steps）
                        PlanDTO mergedPlan = mergeSteps(plan, replanned);
                        tx.executeWithoutResult(status -> {
                            plan.setPlanJson(toJson(mergedPlan));
                            planMapper.updateById(plan);
                        });

                        emit(sse, "plan_updated", Map.of(
                                "planId", plan.getId(),
                                "addedStepCount", replanned.getSteps().size(),
                                "reason", "step_failed_replan"));

                        // 替换剩余步骤继续执行
                        stepDTOs = new ArrayList<>(replanned.getSteps());
                        continue; // 跳过 for 循环的 i++，从新步骤开始
                    } catch (Exception replanEx) {
                        log.error("动态重规划失败", replanEx);
                        // 重规划也失败 → 终止整个计划
                        tx.executeWithoutResult(status -> {
                            plan.setStatus("FAILED");
                            plan.setFinishTime(LocalDateTime.now());
                            planMapper.updateById(plan);
                        });
                        emit(sse, "plan_failed", Map.of(
                                "planId", plan.getId(),
                                "reason", "step_failed_and_replan_failed",
                                "failedStepId", stepDTO.getStepId()));
                        return null;
                    }
                } else {
                    // 无剩余待执行步骤 → 终止计划
                    tx.executeWithoutResult(status -> {
                        plan.setStatus("FAILED");
                        plan.setFinishTime(LocalDateTime.now());
                        planMapper.updateById(plan);
                    });
                    emit(sse, "plan_failed", Map.of(
                            "planId", plan.getId(), "reason", "no_pending_after_failed"));
                    return null;
                }
            }
            lastResult = result;
        }

        // 6. Plan 完成
        if ("RUNNING".equals(plan.getStatus())) {
            tx.executeWithoutResult(status -> markPlanComplete(plan, "COMPLETED"));
            emit(sse, "plan_complete", Map.of(
                    "planId", plan.getId(),
                    "totalSteps", plan.getMaxSteps()));
        }

        return lastResult;
    }

    // ==================== 拓扑排序 + 辅助 ====================

    private List<StepDTO> topoSort(List<StepDTO> steps) {
        if (steps == null || steps.isEmpty()) return List.of();
        Map<String, StepDTO> byId = new LinkedHashMap<>();
        for (StepDTO s : steps) byId.put(s.getStepId(), s);

        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> graph = new HashMap<>();
        for (StepDTO s : steps) { indegree.put(s.getStepId(), 0); graph.put(s.getStepId(), new ArrayList<>()); }
        for (StepDTO s : steps) {
            if (s.getDependsOn() != null) for (String dep : s.getDependsOn()) {
                if (byId.containsKey(dep)) { graph.get(dep).add(s.getStepId()); indegree.merge(s.getStepId(), 1, Integer::sum); }
            }
        }

        Queue<String> queue = indegree.entrySet().stream()
                .filter(e -> e.getValue() == 0).map(Map.Entry::getKey)
                .collect(Collectors.toCollection(ArrayDeque::new));
        List<StepDTO> sorted = new ArrayList<>();
        while (!queue.isEmpty()) {
            String id = queue.poll();
            sorted.add(byId.get(id));
            for (String next : graph.get(id)) {
                indegree.merge(next, -1, Integer::sum);
                if (indegree.get(next) == 0) queue.offer(next);
            }
        }
        if (sorted.size() != steps.size()) {
            log.warn("拓扑排序存在环路，剩余 {} 个追加到末尾", steps.size() - sorted.size());
            Set<String> sortedIds = sorted.stream().map(StepDTO::getStepId).collect(Collectors.toSet());
            for (StepDTO s : steps) if (!sortedIds.contains(s.getStepId())) sorted.add(s);
        }
        return sorted;
    }

    private void persistSteps(List<StepDTO> steps, Long planId) {
        for (StepDTO s : steps) {
            AgentStep step = new AgentStep();
            step.setPlanId(planId);
            step.setStepIndex(s.getStepIndex() != null ? s.getStepIndex() : 0);
            step.setStepId(s.getStepId());
            step.setTitle(s.getTitle());
            step.setDescription(s.getDescription());
            step.setDependsOn(s.getDependsOn() != null ? String.join(",", s.getDependsOn()) : null);
            step.setStatus("PENDING");
            step.setMaxRetries(agentProperties.getDefaultReflectionRetries());
            step.setRetryCount(0);
            stepMapper.insert(step);
        }
    }

    private void markPlanComplete(AgentPlan plan, String status) {
        plan.setStatus(status);
        plan.setFinishTime(LocalDateTime.now());
        planMapper.updateById(plan);
    }

    private List<StepDTO> collectPendingRemaining(Long planId, String afterStepId) {
        return stepMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AgentStep>()
                        .eq(AgentStep::getPlanId, planId)
                        .eq(AgentStep::getStatus, "PENDING")
                        .orderByAsc(AgentStep::getStepIndex))
                .stream().map(s -> {
                    StepDTO dto = new StepDTO();
                    dto.setStepId(s.getStepId());
                    dto.setStepIndex(s.getStepIndex());
                    dto.setTitle(s.getTitle());
                    dto.setDescription(s.getDescription());
                    if (s.getDependsOn() != null) {
                        dto.setDependsOn(Arrays.asList(s.getDependsOn().split(",")));
                    }
                    return dto;
                }).toList();
    }

    private PlanDTO reconstructOriginalPlan(AgentPlan plan) {
        try {
            return objectMapper.readValue(plan.getPlanJson(), new TypeReference<>() {});
        } catch (Exception e) {
            return new PlanDTO();
        }
    }

    private PlanDTO mergeSteps(AgentPlan plan, PlanDTO replanned) {
        try {
            PlanDTO original = objectMapper.readValue(plan.getPlanJson(), new TypeReference<>() {});
            // 替换后续 PENDING 步骤为 replanned steps
            List<String> toRemove = collectPendingStepIds(plan.getId());
            original.getSteps().removeIf(s -> toRemove.contains(s.getStepId()));
            original.getSteps().addAll(replanned.getSteps());
            return original;
        } catch (Exception e) {
            return replanned;
        }
    }

    private List<String> collectPendingStepIds(Long planId) {
        return stepMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AgentStep>()
                        .eq(AgentStep::getPlanId, planId)
                        .in(AgentStep::getStatus, "PENDING", "FAILED"))
                .stream().map(AgentStep::getStepId).toList();
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

    private String toJson(Object obj) {
        if (obj == null) return null;
        try { return objectMapper.writeValueAsString(obj); }
        catch (Exception e) { log.warn("JSON 序列化失败", e); return null; }
    }

    private void emit(BiConsumer<String, Map<String, Object>> sink, String eventName, Map<String, Object> data) {
        if (sink == null) return;
        try { sink.accept(eventName, data); }
        catch (Exception e) { log.warn("SSE 事件发送失败: {}", eventName, e); }
    }
}
