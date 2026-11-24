package com.wgh.rag.workflow.job;

import com.wgh.rag.execution.job.JobContext;
import com.wgh.rag.execution.model.ExecutionLane;
import com.wgh.rag.execution.model.ExecutionSnapshot;
import com.wgh.rag.execution.model.ExecutionStatus;
import com.wgh.rag.execution.run.RunAdapter;
import com.wgh.rag.execution.run.RunTypes;
import com.wgh.rag.workflow.constant.WorkflowConstants;
import com.wgh.rag.workflow.entity.WorkflowRun;
import com.wgh.rag.workflow.mapper.WorkflowRunMapper;
import com.wgh.rag.workflow.service.WorkflowService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;

/**
 * 工作流运行适配器：把 {@code wgh_workflow_run} 接入统一续跑。
 */
@Component
@RequiredArgsConstructor
public class WorkflowRunAdapter implements RunAdapter {

    private final WorkflowRunMapper runMapper;
    private final WorkflowService workflowService;

    @Override
    public String runType() {
        return RunTypes.WORKFLOW;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ExecutionSnapshot> load(String runId) {
        WorkflowRun run = find(runId);
        if (run == null) {
            return Optional.empty();
        }
        return Optional.of(new ExecutionSnapshot(
                String.valueOf(run.getId()),
                String.valueOf(run.getId()),
                RunTypes.WORKFLOW,
                ExecutionLane.BACKGROUND,
                statusOf(run.getStatus()),
                null,
                run.getCreateBy(),
                null,
                0,
                Map.of("executedNodes", run.getExecutedNodes() == null ? 0 : run.getExecutedNodes()),
                run.getErrorMessage(),
                instant(run.getCreateTime()),
                instant(run.getStartTime()),
                instant(run.getFinishTime())));
    }

    @Override
    public void resume(String runId, JobContext context) {
        Long id = parseId(runId);
        if (id == null) {
            return;
        }
        try (com.wgh.rag.execution.run.RunHeartbeat heartbeat =
                     com.wgh.rag.execution.run.RunHeartbeat.start(
                             com.wgh.rag.execution.run.JobRunSupport.lifecycle(context),
                             java.time.Duration.ofSeconds(30))) {
            workflowService.resumeInterrupted(id, context);
            heartbeat.throwIfFailed();
        }
    }

    @Override
    public boolean cancel(String runId) {
        Long id = parseId(runId);
        if (id == null || find(runId) == null) {
            return false;
        }
        workflowService.cancel(id);
        return true;
    }

    private WorkflowRun find(String runId) {
        Long id = parseId(runId);
        return id == null ? null : runMapper.selectById(id);
    }

    private Long parseId(String runId) {
        try {
            return runId == null ? null : Long.valueOf(runId);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private ExecutionStatus statusOf(String status) {
        if (WorkflowConstants.STATUS_RUNNING.equals(status)
                || WorkflowConstants.STATUS_PAUSED.equals(status)
                || WorkflowConstants.STATUS_DEBUG_PAUSED.equals(status)) {
            return ExecutionStatus.RUNNING;
        }
        if (WorkflowConstants.STATUS_COMPLETED.equals(status)) {
            return ExecutionStatus.SUCCEEDED;
        }
        if (WorkflowConstants.STATUS_FAILED.equals(status)) {
            return ExecutionStatus.FAILED;
        }
        if (WorkflowConstants.STATUS_CANCELLED.equals(status)) {
            return ExecutionStatus.CANCELLED;
        }
        return ExecutionStatus.PENDING;
    }

    private java.time.Instant instant(java.time.LocalDateTime time) {
        return time == null ? null : time.atZone(ZoneId.systemDefault()).toInstant();
    }
}
