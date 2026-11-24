package com.wgh.rag.controller.agent;

import com.wgh.rag.agentmulti.support.AgentEventStream;
import com.wgh.rag.execution.event.ExecutionEvent;
import com.wgh.rag.execution.job.JobContext;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 多 Agent 事件的后台作业出口：写入执行事件表（{@code wgh_execution_event}）。
 *
 * <p>前端通过 {@code GET /api/executions/{executionId}/events} attach，按事件序号重放，
 * 断线后用 {@code Last-Event-ID} 续传。执行体不再持有任何 Servlet/SSE 对象。
 */
public class JobAgentEventStream implements AgentEventStream {

    private final JobContext context;

    private final AtomicLong seq = new AtomicLong();

    public JobAgentEventStream(JobContext context) {
        this.context = context;
    }

    @Override
    public void sendEvent(String eventName, Map<String, Object> payload) {
        context.events().emit(new ExecutionEvent(context.executionId(), seq.incrementAndGet(),
                eventName, payload, Instant.now()));
    }

    @Override
    public void sendComment(String comment) {
        context.events().emit(new ExecutionEvent(context.executionId(), seq.incrementAndGet(),
                "COMMENT", Map.of("comment", comment), Instant.now()));
    }
}
