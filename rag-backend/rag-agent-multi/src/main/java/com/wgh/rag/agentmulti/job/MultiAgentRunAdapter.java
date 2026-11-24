package com.wgh.rag.agentmulti.job;

import com.wgh.rag.agentmulti.constant.MultiAgentConstants;
import com.wgh.rag.agentmulti.entity.MultiAgentRun;
import com.wgh.rag.agentmulti.mapper.MultiAgentRunMapper;
import com.wgh.rag.agentmulti.runtime.MultiAgentRuntime;
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
import java.util.Map;
import java.util.Optional;

/**
 * 多 Agent 运行适配器：检查点只写变为续跑时会读，事件出口允许 {@code null}。
 */
@Component
@RequiredArgsConstructor
public class MultiAgentRunAdapter implements RunAdapter {

    private final MultiAgentRunMapper runMapper;
    private final MultiAgentRuntime runtime;

    @Override
    public String runType() {
        return RunTypes.MULTI_AGENT;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ExecutionSnapshot> load(String runId) {
        MultiAgentRun run = runMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MultiAgentRun>()
                .eq(MultiAgentRun::getRunId, runId)
                .last("LIMIT 1"));
        if (run == null) {
            return Optional.empty();
        }
        return Optional.of(new ExecutionSnapshot(
                run.getRunId(),
                run.getRunId(),
                RunTypes.MULTI_AGENT,
                ExecutionLane.BACKGROUND,
                statusOf(run.getStatus()),
                null,
                run.getCreateBy(),
                null,
                0,
                Map.of("currentRound", run.getCurrentRound() == null ? 0 : run.getCurrentRound()),
                run.getErrorMessage(),
                instant(run.getCreateTime()),
                instant(run.getStartTime()),
                instant(run.getFinishTime())));
    }

    @Override
    public void resume(String runId, JobContext context) {
        MultiAgentRun run = find(runId);
        if (run == null) {
            return;
        }
        if (!MultiAgentConstants.STATUS_RUNNING.equals(run.getStatus())
                && !"PLANNING".equals(run.getStatus())) {
            // PAUSED 属 HITL，终态幂等返回
            return;
        }
        try (com.wgh.rag.execution.run.RunHeartbeat heartbeat =
                     com.wgh.rag.execution.run.RunHeartbeat.start(
                             JobRunSupport.lifecycle(context), java.time.Duration.ofSeconds(30))) {
            runtime.resumeFromCheckpoint(runId, null, JobRunSupport.lifecycle(context));
            heartbeat.throwIfFailed();
        }
    }

    @Override
    public boolean cancel(String runId) {
        if (find(runId) == null) {
            return false;
        }
        runtime.cancelRun(runId);
        return true;
    }

    private MultiAgentRun find(String runId) {
        if (runId == null || runId.isBlank()) {
            return null;
        }
        return runMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MultiAgentRun>()
                .eq(MultiAgentRun::getRunId, runId)
                .last("LIMIT 1"));
    }

    private ExecutionStatus statusOf(String status) {
        if (MultiAgentConstants.STATUS_RUNNING.equals(status) || "PLANNING".equals(status)) {
            return ExecutionStatus.RUNNING;
        }
        if (MultiAgentConstants.STATUS_COMPLETED.equals(status)) {
            return ExecutionStatus.SUCCEEDED;
        }
        if (MultiAgentConstants.STATUS_FAILED.equals(status)) {
            return ExecutionStatus.FAILED;
        }
        if (MultiAgentConstants.STATUS_CANCELLED.equals(status)) {
            return ExecutionStatus.CANCELLED;
        }
        return ExecutionStatus.PENDING;
    }

    private java.time.Instant instant(java.time.LocalDateTime time) {
        return time == null ? null : time.atZone(ZoneId.systemDefault()).toInstant();
    }
}
