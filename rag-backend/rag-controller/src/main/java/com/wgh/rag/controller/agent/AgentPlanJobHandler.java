package com.wgh.rag.controller.agent;

import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.execution.job.JobContext;
import com.wgh.rag.execution.job.JobHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 单 Agent HITL 续跑作业（{@code AGENT_PLAN}，后台车道）。
 *
 * <p>把「确认后继续执行」从请求线程移到后台：确认接口返回 {@code executionId}，
 * 前端轮询 {@code /api/agent/plans/{planId}/state} 或 {@code /api/executions/{executionId}}。
 * 步骤确认由 {@link com.wgh.rag.agent.service.AgentHitlConfirmService#prepare} 原子闸门保证只执行一次；
 * 作业重试遇到「已非 HITL_PENDING」按幂等成功处理，避免重复扣费/重复续跑。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentPlanJobHandler implements JobHandler {

    /** 作业类型。 */
    public static final String JOB_TYPE = "AGENT_PLAN";

    /** 负载字段：步骤主键。 */
    public static final String KEY_STEP_ID = "stepId";
    /** 负载字段：用户补充输入。 */
    public static final String KEY_USER_INPUT = "userInput";

    private final AgentHitlResumeService resumeService;

    @Override
    public String jobType() {
        return JOB_TYPE;
    }

    @Override
    public void execute(JobContext context) {
        Map<String, Object> payload = context.request().payload().data();
        Long stepId = longValue(payload.get(KEY_STEP_ID));
        if (stepId == null) {
            throw new IllegalArgumentException("Agent 续跑作业缺少 stepId");
        }
        String userInput = payload.get(KEY_USER_INPUT) == null
                ? "" : String.valueOf(payload.get(KEY_USER_INPUT));
        try {
            Map<String, Object> result = resumeService.resume(stepId, userInput);
            context.saveResult(result);
        } catch (BusinessException e) {
            // 重复确认/已被其它执行推进：视为幂等成功，不重复扣费
            log.info("Agent 续跑作业跳过（步骤状态已推进） executionId={} stepId={} reason={}",
                    context.executionId(), stepId, e.getMessage());
            context.saveResult(Map.of("stepId", stepId, "skipped", true,
                    "reason", e.getMessage() == null ? "ALREADY_RESUMED" : e.getMessage()));
        }
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Long.parseLong(text.trim());
        }
        return null;
    }
}
