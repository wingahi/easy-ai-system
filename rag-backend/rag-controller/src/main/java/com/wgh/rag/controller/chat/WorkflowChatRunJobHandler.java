package com.wgh.rag.controller.chat;

import com.wgh.rag.execution.event.ExecutionEvent;
import com.wgh.rag.execution.job.JobContext;
import com.wgh.rag.execution.job.JobHandler;
import com.wgh.rag.workflow.entity.WorkflowRun;
import com.wgh.rag.workflow.service.WorkflowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 聊天工作流运行作业（{@code WF_CHAT_RUN}，后台车道）。
 *
 * <p>工作流事件通过执行事件表输出，聊天 SSE 由调用方 attach 并映射回聊天协议；
 * 执行体不依赖任何 HTTP/SSE 对象，断线重连由 {@code Last-Event-ID} 补齐。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowChatRunJobHandler implements JobHandler {

    /** 作业类型。 */
    public static final String JOB_TYPE = "WF_CHAT_RUN";

    /** 负载字段。 */
    public static final String KEY_WORKFLOW_ID = "workflowId";
    public static final String KEY_INPUT = "input";
    public static final String KEY_SESSION_ID = "sessionId";
    public static final String KEY_SAFE_MODE = "safeMode";

    private final WorkflowService workflowService;

    @Override
    public String jobType() {
        return JOB_TYPE;
    }

    @Override
    public void execute(JobContext context) {
        Map<String, Object> payload = context.request().payload().data();
        Long workflowId = longValue(payload.get(KEY_WORKFLOW_ID));
        if (workflowId == null) {
            throw new IllegalArgumentException("聊天工作流作业缺少 workflowId");
        }
        String input = payload.get(KEY_INPUT) == null ? "" : String.valueOf(payload.get(KEY_INPUT));
        Long sessionId = longValue(payload.get(KEY_SESSION_ID));
        boolean safeMode = Boolean.TRUE.equals(payload.get(KEY_SAFE_MODE));

        AtomicLong seq = new AtomicLong();
        WorkflowRun run = workflowService.runForChatBlocking(workflowId, input, sessionId, safeMode,
                (event, eventPayload) -> context.events().emit(new ExecutionEvent(
                        context.executionId(), seq.incrementAndGet(), event,
                        eventPayload, Instant.now())));
        context.saveResult(Map.of("runId", run.getId()));
        log.info("聊天工作流后台运行结束 executionId={} runId={}", context.executionId(), run.getId());
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
