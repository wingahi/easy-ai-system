package com.wgh.rag.agent.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wgh.rag.agent.config.AgentProperties;
import com.wgh.rag.agent.constant.AgentConstants;
import com.wgh.rag.agent.entity.AgentPlan;
import com.wgh.rag.agent.entity.AgentStep;
import com.wgh.rag.agent.mapper.AgentPlanMapper;
import com.wgh.rag.agent.mapper.AgentStepMapper;
import com.wgh.rag.agent.service.AgentOrchestrator;
import com.wgh.rag.execution.job.JobContext;
import com.wgh.rag.execution.model.ExecutionLane;
import com.wgh.rag.execution.model.ExecutionSnapshot;
import com.wgh.rag.execution.model.ExecutionStatus;
import com.wgh.rag.execution.run.JobRunSupport;
import com.wgh.rag.execution.run.RunAdapter;
import com.wgh.rag.execution.run.RunTypes;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 单 Agent 计划适配器：复用 {@code resumeAfterInterruption} 的 RUNNING→PENDING 复位。
 */
@Component
@RequiredArgsConstructor
public class AgentPlanRunAdapter implements RunAdapter {

    private final AgentPlanMapper planMapper;
    private final AgentStepMapper stepMapper;
    private final AgentOrchestrator orchestrator;
    private final AgentProperties agentProperties;

    @Override
    public String runType() {
        return RunTypes.AGENT;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ExecutionSnapshot> load(String runId) {
        AgentPlan plan = find(runId);
        if (plan == null) {
            return Optional.empty();
        }
        long successSteps = stepMapper.selectCount(new LambdaQueryWrapper<AgentStep>()
                .eq(AgentStep::getPlanId, plan.getId())
                .eq(AgentStep::getStatus, AgentConstants.STATUS_SUCCESS));
        return Optional.of(new ExecutionSnapshot(
                String.valueOf(plan.getId()),
                String.valueOf(plan.getId()),
                RunTypes.AGENT,
                ExecutionLane.BACKGROUND,
                statusOf(plan.getStatus()),
                null,
                plan.getCreateBy(),
                null,
                0,
                Map.of("successSteps", successSteps),
                null,
                instant(plan.getCreateTime()),
                null,
                instant(plan.getFinishTime())));
    }

    @Override
    public void resume(String runId, JobContext context) {
        Long id = parseId(runId);
        AgentPlan plan = id == null ? null : planMapper.selectById(id);
        if (plan == null || !AgentConstants.STATUS_RUNNING.equals(plan.getStatus())) {
            return;
        }
        // HITL 暂停的步骤必须走人工确认接口，调度续跑不得消费
        List<AgentStep> steps = stepMapper.selectList(new LambdaQueryWrapper<AgentStep>()
                .eq(AgentStep::getPlanId, id));
        if (steps.stream().anyMatch(s -> AgentConstants.STATUS_HITL_PENDING.equals(s.getStatus()))) {
            return;
        }
        try (com.wgh.rag.execution.run.RunHeartbeat heartbeat =
                     com.wgh.rag.execution.run.RunHeartbeat.start(
                             JobRunSupport.lifecycle(context), java.time.Duration.ofSeconds(30))) {
            orchestrator.resumeAfterInterruption(id,
                    agentProperties.getDefaultReflectionThreshold(),
                    agentProperties.getDefaultReflectionRetries(),
                    JobRunSupport.events(context), null, null, JobRunSupport.lifecycle(context));
            heartbeat.throwIfFailed();
        }
    }

    @Override
    public boolean cancel(String runId) {
        Long id = parseId(runId);
        if (id == null || planMapper.selectById(id) == null) {
            return false;
        }
        orchestrator.cancel(id, (event, payload) -> { });
        return true;
    }

    private AgentPlan find(String runId) {
        Long id = parseId(runId);
        return id == null ? null : planMapper.selectById(id);
    }

    private Long parseId(String runId) {
        try {
            return runId == null ? null : Long.valueOf(runId);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private ExecutionStatus statusOf(String status) {
        if (AgentConstants.STATUS_RUNNING.equals(status)) {
            return ExecutionStatus.RUNNING;
        }
        if (AgentConstants.STATUS_COMPLETED.equals(status)) {
            return ExecutionStatus.SUCCEEDED;
        }
        if (AgentConstants.STATUS_FAILED.equals(status)) {
            return ExecutionStatus.FAILED;
        }
        if (AgentConstants.STATUS_CANCELLED.equals(status)) {
            return ExecutionStatus.CANCELLED;
        }
        return ExecutionStatus.PENDING;
    }

    private java.time.Instant instant(java.time.LocalDateTime time) {
        return time == null ? null : time.atZone(ZoneId.systemDefault()).toInstant();
    }
}
