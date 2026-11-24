package com.wgh.rag.controller.agent;

import com.wgh.rag.agentmulti.runtime.MultiAgentRuntime;
import com.wgh.rag.execution.job.JobContext;
import com.wgh.rag.execution.job.JobHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 多 Agent 运行作业（{@code AGENT_MULTI_RUN}，后台车道）。
 *
 * <p>事件出口改为 {@link JobAgentEventStream}（写执行事件表），不再绑定 {@code SseEmitter}；
 * 前端通过 {@code /api/executions/{executionId}/events} attach / Last-Event-ID 重放。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MultiAgentRunJobHandler implements JobHandler {

    /** 作业类型。 */
    public static final String JOB_TYPE = "AGENT_MULTI_RUN";

    /** 幂等键前缀：一次 run 对应一次执行。 */
    public static final String BIZ_KEY_PREFIX = "multiagent:";

    /** 负载字段：Run ID。 */
    public static final String KEY_RUN_ID = "runId";

    private final MultiAgentRuntime runtime;

    @Override
    public String jobType() {
        return JOB_TYPE;
    }

    @Override
    public void execute(JobContext context) {
        Object rawRunId = context.request().payload().data().get(KEY_RUN_ID);
        String runId = rawRunId == null ? "" : String.valueOf(rawRunId);
        if (runId.isBlank()) {
            throw new IllegalArgumentException("多 Agent 作业缺少 runId");
        }
        String finishedRunId = runtime.execute(runId, new JobAgentEventStream(context));
        context.saveResult(Map.of(KEY_RUN_ID, finishedRunId));
        log.info("多 Agent 后台运行结束 executionId={} runId={}", context.executionId(), finishedRunId);
    }
}
