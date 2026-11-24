package com.wgh.rag.agent.service;

import com.wgh.rag.agent.constant.AgentConstants;
import com.wgh.rag.agent.entity.AgentPlan;
import com.wgh.rag.agent.entity.AgentStep;
import com.wgh.rag.agent.exception.AgentException;
import com.wgh.rag.agent.mapper.AgentPlanMapper;
import com.wgh.rag.agent.mapper.AgentStepMapper;
import com.wgh.rag.common.security.SecurityUtils;
import com.wgh.rag.quota.service.QuotaChargeTracker;
import com.wgh.rag.quota.service.QuotaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Agent 计划 HITL 确认的「推进状态 + 扣费」事务边界。
 *
 * <p>确认校验、步骤/计划状态推进、配额计数与扣费流水在同一事务内提交：
 * 任一失败整体回滚，不会出现「状态已推进但未扣费」或「已扣费但确认失败」。
 * 幂等闸门落在条件 UPDATE 上：同一 HITL 步骤只允许推进一次。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentHitlConfirmService {

    private final AgentPlanMapper planMapper;
    private final AgentStepMapper stepMapper;
    private final QuotaService quotaService;
    private final QuotaChargeTracker chargeTracker;

    /**
     * 原子确认 HITL 步骤并扣费一次。
     *
     * @param stepId    HITL_PENDING 步骤的主键
     * @param userInput 用户确认输入
     * @return 已推进的 plan/step 与计费句柄，供调用方执行后续步骤并结算/退款
     */
    @Transactional
    public Claim prepare(Long stepId, String userInput) {
        AgentStep step = stepMapper.selectById(stepId);
        if (step == null) {
            throw new AgentException("Step 不存在");
        }
        AgentPlan plan = planMapper.selectById(step.getPlanId());
        if (plan == null) {
            throw new AgentException("Plan 不存在");
        }
        String confirmInput = userInput == null ? "" : userInput;
        if (stepMapper.claimHitlPending(stepId, confirmInput) == 0) {
            throw new AgentException("Step 非 HITL_PENDING 状态或已完成确认");
        }
        if (planMapper.claimPausedForResume(plan.getId()) == 0) {
            throw new AgentException("Plan 非 PAUSED 状态，无法 resume");
        }

        step.setStatus(AgentConstants.STATUS_SUCCESS);
        step.setResultSummary(confirmInput);
        plan.setStatus(AgentConstants.STATUS_RUNNING);

        // 原对话本轮已结算则续跑不再扣费；同一计划再次暂停时复用未决流水
        LocalDateTime roundStart = plan.getCreateTime() != null ? plan.getCreateTime() : plan.getUpdateTime();
        QuotaService.ResumeChargeReservation reservation = quotaService.reserveResumeCharge(
                SecurityUtils.currentUsername(), plan.getChatWindowId(),
                "hitl:agent-plan:" + plan.getId(), plan.getSessionId(), roundStart);
        quotaService.attachSession(reservation.charge(), plan.getSessionId());
        log.info("Agent HITL 确认计费: planId={}, stepId={}, alreadySettled={}, charge={}",
                plan.getId(), stepId, reservation.alreadySettled(),
                reservation.charge() == null ? null : reservation.charge().chargeId());
        return new Claim(plan, step, chargeTracker.track(reservation.charge()));
    }

    /** 确认结果：已推进的 plan/step 与计费句柄。 */
    public record Claim(AgentPlan plan, AgentStep step, QuotaChargeTracker.Handle billing) {
    }
}
