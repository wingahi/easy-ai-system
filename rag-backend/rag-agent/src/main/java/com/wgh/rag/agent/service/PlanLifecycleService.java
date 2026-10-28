package com.wgh.rag.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.agent.config.AgentProperties;
import com.wgh.rag.agent.dto.AgentStepResult;
import com.wgh.rag.agent.dto.PlanDTO;
import com.wgh.rag.agent.dto.PlanDTO.StepDTO;
import com.wgh.rag.agent.entity.AgentPlan;
import com.wgh.rag.agent.entity.AgentStep;
import com.wgh.rag.agent.exception.AgentException;
import com.wgh.rag.agent.mapper.AgentPlanMapper;
import com.wgh.rag.agent.mapper.AgentStepMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * Plan/Step 持久化与状态机迁移。
 *
 * 事务策略（与原 AgentOrchestrator 一致）：
 * - 不用外层 @Transactional。标注 Tx 的方法各自开启独立小事务。
 * - 每个 step 的执行结果持久化：单独事务（失败时只回滚该步，不影响已完成步骤）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlanLifecycleService {

    private final AgentPlanMapper planMapper;
    private final AgentStepMapper stepMapper;
    private final AgentProperties agentProperties;
    private final ObjectMapper objectMapper;
    private final PlatformTransactionManager transactionManager;

    // ==================== 查询 ====================

    public AgentPlan findPlan(Long planId) {
        return planMapper.selectById(planId);
    }

    public AgentStep findStep(Long planId, String stepId) {
        return stepMapper.selectOne(new LambdaQueryWrapper<AgentStep>()
                .eq(AgentStep::getPlanId, planId)
                .eq(AgentStep::getStepId, stepId)
                .last("LIMIT 1"));
    }

    /** 按 stepIndex 升序加载某 plan 的全部未删除步骤。 */
    public List<AgentStep> loadSteps(Long planId) {
        return stepMapper.selectList(new LambdaQueryWrapper<AgentStep>()
                .eq(AgentStep::getPlanId, planId)
                .eq(AgentStep::getDeleted, 0)
                .orderByAsc(AgentStep::getStepIndex));
    }

    /** 加载某步骤之前的全部 SUCCESS 步骤（retryStep 重建上下文用）。 */
    public List<AgentStep> loadSucceededStepsBefore(Long planId, int stepIndex) {
        return stepMapper.selectList(new LambdaQueryWrapper<AgentStep>()
                .eq(AgentStep::getPlanId, planId)
                .lt(AgentStep::getStepIndex, stepIndex)
                .eq(AgentStep::getStatus, "SUCCESS")
                .orderByAsc(AgentStep::getStepIndex));
    }

    /** 收集某 plan 下全部 PENDING 步骤，转成 StepDTO。 */
    public List<StepDTO> collectPendingRemaining(Long planId) {
        return stepMapper.selectList(new LambdaQueryWrapper<AgentStep>()
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

    // ==================== plan/step 创建 ====================

    /** 持久化 plan（独立小事务）。 */
    public AgentPlan createPlanTx(Long sessionId, Long windowId, Long windowModelId,
                                  String userGoal, PlanDTO planDTO) {
        return tx().execute(status -> {
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
    }

    /** 逐条持久化 steps（与原实现一致：不开事务）。 */
    public void persistSteps(List<StepDTO> steps, Long planId) {
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

    /** 重规划产生的新步骤逐条入库（与原实现一致：不开事务）。 */
    public void insertReplannedSteps(Long planId, List<StepDTO> newSteps, int fromStepIndex) {
        int newStepIndex = fromStepIndex;
        for (StepDTO newStep : newSteps) {
            AgentStep newDbStep = new AgentStep();
            newDbStep.setPlanId(planId);
            newDbStep.setStepIndex(newStepIndex++);
            newDbStep.setStepId(newStep.getStepId());
            newDbStep.setTitle(newStep.getTitle());
            newDbStep.setDescription(newStep.getDescription());
            newDbStep.setDependsOn(newStep.getDependsOn() != null ? String.join(",", newStep.getDependsOn()) : null);
            newDbStep.setStatus("PENDING");
            newDbStep.setMaxRetries(agentProperties.getDefaultReflectionRetries());
            stepMapper.insert(newDbStep);
        }
    }

    // ==================== 状态机迁移 ====================

    public void updatePlan(AgentPlan plan) {
        planMapper.updateById(plan);
    }

    public void updateStep(AgentStep step) {
        stepMapper.updateById(step);
    }

    /** 步骤开始执行：RUNNING + startTime，同步 plan.currentStepIndex（独立小事务）。 */
    public void markStepRunningTx(AgentPlan plan, AgentStep dbStep) {
        tx().executeWithoutResult(status -> {
            dbStep.setStatus("RUNNING");
            dbStep.setStartTime(LocalDateTime.now());
            stepMapper.updateById(dbStep);
            plan.setCurrentStepIndex(dbStep.getStepIndex());
            planMapper.updateById(plan);
        });
    }

    /** HITL 暂停持久化：step=HITL_PENDING，plan=PAUSED（独立小事务）。 */
    public void markHitlPendingTx(AgentPlan plan, AgentStep dbStep, StepDTO stepDTO) {
        tx().executeWithoutResult(status -> {
            dbStep.setStatus("HITL_PENDING");
            dbStep.setThought("HITL 节点等待用户确认: " + stepDTO.getDescription());
            stepMapper.updateById(dbStep);
            plan.setStatus("PAUSED");
            planMapper.updateById(plan);
        });
    }

    /** Reflection 重试次数落库（独立小事务）。 */
    public void updateRetryCountTx(AgentStep dbStep, int retries) {
        tx().executeWithoutResult(status -> {
            dbStep.setRetryCount(retries);
            stepMapper.updateById(dbStep);
        });
    }

    /** Reflection 达标分数落库（独立小事务）。 */
    public void saveReflectionScoreTx(AgentStep dbStep, double score, String feedback) {
        tx().executeWithoutResult(status -> {
            dbStep.setReflectionScore(score);
            dbStep.setReflectionFeedback(feedback);
            stepMapper.updateById(dbStep);
        });
    }

    /** 步骤执行结果持久化 + 状态机迁移（独立小事务；失败只回滚该步）。 */
    public void persistStepResultTx(AgentPlan plan, AgentStep dbStep, StepDTO stepDTO, AgentStepResult result) {
        tx().executeWithoutResult(status -> {
            dbStep.setThought(result.getThoughts() != null && !result.getThoughts().isEmpty()
                    ? String.join("\n", result.getThoughts()) : null);
            dbStep.setObservation(toJson(result.getToolInvocations()));
            dbStep.setResultSummary(result.getResultSummary());
            dbStep.setEndTime(LocalDateTime.now());
            dbStep.setTokenCount(result.getTokenCount());
            dbStep.setActionTool(stepDTO.getRequiresTools() != null
                    ? String.join(",", stepDTO.getRequiresTools()) : null);

            switch (result.getStatus()) {
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
    }

    /** plan 终态 + finishTime（无事务，与原 orchestrate 空计划路径一致）。 */
    public void markPlanComplete(AgentPlan plan, String status) {
        plan.setStatus(status);
        plan.setFinishTime(LocalDateTime.now());
        planMapper.updateById(plan);
    }

    /** plan 终态 + finishTime（独立小事务，与原 executeSteps 收尾路径一致）。 */
    public void markPlanCompleteTx(AgentPlan plan, String status) {
        tx().executeWithoutResult(s -> markPlanComplete(plan, status));
    }

    /** plan 置 FAILED + finishTime（独立小事务）。 */
    public void failPlanTx(AgentPlan plan) {
        tx().executeWithoutResult(status -> {
            plan.setStatus("FAILED");
            plan.setFinishTime(LocalDateTime.now());
            planMapper.updateById(plan);
        });
    }

    /** 取消 plan 并把全部 RUNNING / PENDING / HITL_PENDING 步骤标记 SKIPPED（无事务，与原实现一致）。 */
    public void cancelPlanAndSkipUnfinishedSteps(AgentPlan plan) {
        plan.setStatus("CANCELLED");
        plan.setFinishTime(LocalDateTime.now());
        planMapper.updateById(plan);
        stepMapper.update(null, new LambdaUpdateWrapper<AgentStep>()
                .eq(AgentStep::getPlanId, plan.getId())
                .in(AgentStep::getStatus, "RUNNING", "PENDING", "HITL_PENDING")
                .set(AgentStep::getStatus, "SKIPPED"));
    }

    // ==================== plan_json 读写 ====================

    /** 解析 plan_json；失败抛 AgentException（resume/retryStep 语义）。 */
    public PlanDTO parsePlanJsonOrThrow(AgentPlan plan) {
        try {
            return objectMapper.readValue(plan.getPlanJson(), new TypeReference<>() {});
        } catch (Exception e) {
            throw new AgentException("Plan JSON 解析失败", e);
        }
    }

    /** 解析 plan_json；失败返回空 PlanDTO（replan 时重建原计划语义）。 */
    public PlanDTO parsePlanJsonOrEmpty(AgentPlan plan) {
        try {
            return objectMapper.readValue(plan.getPlanJson(), new TypeReference<>() {});
        } catch (Exception e) {
            return new PlanDTO();
        }
    }

    /** 合并 replanned 步骤：移除当前 PENDING/FAILED 步骤，追加 replanned（独立小事务落库 plan_json）。 */
    public PlanDTO mergeStepsAndSaveTx(AgentPlan plan, PlanDTO replanned) {
        PlanDTO merged;
        try {
            PlanDTO original = objectMapper.readValue(plan.getPlanJson(), new TypeReference<>() {});
            List<String> toRemove = collectPendingStepIds(plan.getId());
            original.getSteps().removeIf(s -> toRemove.contains(s.getStepId()));
            original.getSteps().addAll(replanned.getSteps());
            merged = original;
        } catch (Exception e) {
            merged = replanned;
        }
        PlanDTO finalMerged = merged;
        tx().executeWithoutResult(status -> {
            plan.setPlanJson(toJson(finalMerged));
            planMapper.updateById(plan);
        });
        return merged;
    }

    private List<String> collectPendingStepIds(Long planId) {
        return stepMapper.selectList(new LambdaQueryWrapper<AgentStep>()
                        .eq(AgentStep::getPlanId, planId)
                        .in(AgentStep::getStatus, "PENDING", "FAILED"))
                .stream().map(AgentStep::getStepId).toList();
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    private String toJson(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.warn("JSON 序列化失败", e);
            return null;
        }
    }
}
