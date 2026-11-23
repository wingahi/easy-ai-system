package com.wgh.rag.workflow.job;

import com.wgh.rag.execution.error.ExecutionErrorCodes;
import com.wgh.rag.execution.error.ExecutionException;
import com.wgh.rag.execution.event.ExecutionEvent;
import com.wgh.rag.execution.event.ExecutionEventTypes;
import com.wgh.rag.execution.job.JobContext;
import com.wgh.rag.execution.job.JobHandler;
import com.wgh.rag.execution.model.JobPayload;
import com.wgh.rag.workflow.entity.WorkflowRun;
import com.wgh.rag.workflow.service.WorkflowService;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/**
 * 工作流后台作业处理器（{@code WF_RUN}）。
 *
 * <p>由定时触发器提交，执行体只依赖 {@code rag-execution} 契约，不感知调度实现。
 * 同步执行：作业的租约、超时与取消由调度层负责；本处理器在开始与结束处检查取消标记。
 *
 * <p>幂等说明：同一触发 slot 通过 {@code bizKey} 去重；首次创建 run 后立即把 runId 写入
 * 作业 {@code result_json}，租约回收后的重试按该 runId 走中断续跑，不再新建运行记录。
 */
@Component
public class WorkflowRunJobHandler implements JobHandler {

    /** 作业类型。 */
    public static final String JOB_TYPE = "WF_RUN";

    /** 负载字段：工作流 ID。 */
    public static final String PAYLOAD_WORKFLOW_ID = "workflowId";

    /** 负载字段：运行输入。 */
    public static final String PAYLOAD_INPUT = "input";

    /** 负载字段：触发器 ID（仅用于排查）。 */
    public static final String PAYLOAD_TRIGGER_ID = "triggerId";

    private final WorkflowService workflowService;

    public WorkflowRunJobHandler(WorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    @Override
    public String jobType() {
        return JOB_TYPE;
    }

    @Override
    public void execute(JobContext context) {
        JobPayload payload = context.payload();
        String workflowIdText = payload.getString(PAYLOAD_WORKFLOW_ID);
        if (workflowIdText == null || workflowIdText.isBlank()) {
            throw new ExecutionException(ExecutionErrorCodes.INCOMPATIBLE_PAYLOAD,
                    "WF_RUN 负载缺少 workflowId", context.executionId());
        }
        long workflowId;
        try {
            workflowId = Long.parseLong(workflowIdText);
        } catch (NumberFormatException e) {
            throw new ExecutionException(ExecutionErrorCodes.INCOMPATIBLE_PAYLOAD,
                    "WF_RUN 负载 workflowId 非法: " + workflowIdText, context.executionId());
        }
        context.cancellation().throwIfCancellationRequested();

        // 租约回收后的重试：result_json 已保存首次创建的 runId，按原 run 续跑而不是新建
        Object existingRunId = context.savedResult().get("runId");
        if (existingRunId != null) {
            long runId;
            try {
                runId = Long.parseLong(String.valueOf(existingRunId));
            } catch (NumberFormatException e) {
                throw new ExecutionException(ExecutionErrorCodes.INCOMPATIBLE_PAYLOAD,
                        "WF_RUN 已保存的 runId 非法: " + existingRunId, context.executionId());
            }
            WorkflowRun run = workflowService.resumeInterrupted(runId, context);
            context.saveResult(Map.of("runId", run.getId(), "status", run.getStatus()));
            context.events().emit(new ExecutionEvent(context.executionId(), 1, ExecutionEventTypes.PROGRESS,
                    Map.of("event", "workflow.run.resumed", "runId", String.valueOf(run.getId())),
                    Instant.now()));
            return;
        }

        WorkflowRun run = workflowService.runHeadlessBlocking(workflowId, payload.getString(PAYLOAD_INPUT),
                created -> context.saveResult(Map.of("runId", created.getId())));
        context.saveResult(Map.of("runId", run.getId(), "status", run.getStatus()));
        context.cancellation().throwIfCancellationRequested();
        context.events().emit(new ExecutionEvent(context.executionId(), 1, ExecutionEventTypes.PROGRESS,
                Map.of("event", "workflow.run.completed", "runId", String.valueOf(run.getId())),
                Instant.now()));
    }
}
