package com.wgh.rag.controller.chat;

import com.wgh.rag.execution.event.ExecutionEvent;
import com.wgh.rag.execution.job.JobContext;
import com.wgh.rag.execution.job.JobHandler;
import com.wgh.rag.workflow.service.WorkflowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 聊天工作流 HITL 续跑作业（{@code WF_CHAT_HITL}，后台车道）。
 *
 * <p>确认与扣费在独立事务中提交后，于作业线程同步续跑；事件写入执行事件表，
 * 聊天 SSE 由 {@code ChatWorkflowService} attach 并映射回聊天协议。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowChatHitlJobHandler implements JobHandler {

    /** 作业类型。 */
    public static final String JOB_TYPE = "WF_CHAT_HITL";

    /** 负载字段：运行 ID。 */
    public static final String KEY_RUN_ID = "runId";

    /** 负载字段：用户确认输入。 */
    public static final String KEY_USER_INPUT = "userInput";

    private final WorkflowService workflowService;

    @Override
    public String jobType() {
        return JOB_TYPE;
    }

    @Override
    public void execute(JobContext context) {
        Map<String, Object> payload = context.request().payload().data();
        Object rawRunId = payload.get(KEY_RUN_ID);
        if (rawRunId == null || String.valueOf(rawRunId).isBlank()) {
            throw new IllegalArgumentException("聊天工作流续跑作业缺少 runId");
        }
        Long runId = Long.valueOf(String.valueOf(rawRunId));
        String userInput = payload.get(KEY_USER_INPUT) == null
                ? "" : String.valueOf(payload.get(KEY_USER_INPUT));

        AtomicLong seq = new AtomicLong();
        workflowService.resumeForChatBlocking(runId, userInput,
                (event, eventPayload) -> context.events().emit(new ExecutionEvent(
                        context.executionId(), seq.incrementAndGet(), event,
                        eventPayload, Instant.now())));
        context.saveResult(Map.of(KEY_RUN_ID, runId));
        log.info("聊天工作流后台续跑结束 executionId={} runId={}", context.executionId(), runId);
    }
}
