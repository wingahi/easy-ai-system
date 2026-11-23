package com.wgh.rag.controller.service.resume;

import com.wgh.rag.execution.error.ExecutionErrorCodes;
import com.wgh.rag.execution.error.ExecutionException;
import com.wgh.rag.execution.job.JobContext;
import com.wgh.rag.execution.job.JobHandler;
import com.wgh.rag.execution.model.JobPayload;
import com.wgh.rag.execution.run.RunAdapter;
import com.wgh.rag.execution.run.RunRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 单 run 续跑作业（{@code RUN_RESUME}）。
 *
 * <p>负载：{@code runType}、{@code runId}、{@code reason}、{@code detectedAt}。
 * 调度层只经 {@link RunRegistry} 查找适配器，不按类型 switch。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RunResumeJobHandler implements JobHandler {

    public static final String PAYLOAD_RUN_TYPE = "runType";
    public static final String PAYLOAD_RUN_ID = "runId";

    private final RunRegistry runRegistry;

    @Override
    public String jobType() {
        return ResumeJobTypes.RUN_RESUME;
    }

    @Override
    public void execute(JobContext context) {
        JobPayload payload = context.payload();
        String runType = payload.getString(PAYLOAD_RUN_TYPE);
        String runId = payload.getString(PAYLOAD_RUN_ID);
        if (runType == null || runType.isBlank() || runId == null || runId.isBlank()) {
            throw new ExecutionException(ExecutionErrorCodes.INCOMPATIBLE_PAYLOAD,
                    "RUN_RESUME 负载缺少 runType/runId", context.executionId());
        }
        RunAdapter adapter = runRegistry.find(runType)
                .orElseThrow(() -> new ExecutionException(ExecutionErrorCodes.INCOMPATIBLE_PAYLOAD,
                        "未注册的 runType: " + runType, context.executionId()));
        log.info("续跑作业开始 executionId={} runType={} runId={}",
                context.executionId(), runType, runId);
        adapter.resume(runId, context);
    }
}
