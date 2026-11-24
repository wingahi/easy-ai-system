package com.wgh.rag.controller.agent;

import com.wgh.rag.agentmulti.constant.MultiAgentConstants;
import com.wgh.rag.agentmulti.entity.MultiAgentRun;
import com.wgh.rag.agentmulti.runtime.MultiAgentRuntime;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.execution.job.JobContext;
import com.wgh.rag.execution.job.JobHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 多 Agent HITL 续跑作业（{@code AGENT_MULTI_HITL}，后台车道）。
 *
 * <p>确认闸门（{@code prepareHitlResume}）与续跑都在作业内完成，保证只扣费一次；
 * 计费结算口径与 REST 同步路径一致：再次停在 HITL 保持未结算，否则结算。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MultiAgentHitlJobHandler implements JobHandler {

    /** 作业类型。 */
    public static final String JOB_TYPE = "AGENT_MULTI_HITL";

    /** 负载字段：Run ID。 */
    public static final String KEY_RUN_ID = "runId";

    /** 负载字段：用户补充输入。 */
    public static final String KEY_USER_INPUT = "userInput";

    private final MultiAgentRuntime runtime;

    @Override
    public String jobType() {
        return JOB_TYPE;
    }

    @Override
    public void execute(JobContext context) {
        Map<String, Object> payload = context.request().payload().data();
        Object rawRunId = payload.get(KEY_RUN_ID);
        String runId = rawRunId == null ? "" : String.valueOf(rawRunId);
        if (runId.isBlank()) {
            throw new IllegalArgumentException("多 Agent 续跑作业缺少 runId");
        }
        String userInput = payload.get(KEY_USER_INPUT) == null
                ? "" : String.valueOf(payload.get(KEY_USER_INPUT));

        MultiAgentRuntime.HitlResumeClaim claim;
        try {
            claim = runtime.prepareHitlResume(runId);
        } catch (BusinessException e) {
            // 重复确认/已被其它执行推进：按幂等成功处理，不重复扣费
            log.info("多 Agent 续跑作业跳过 executionId={} runId={} reason={}",
                    context.executionId(), runId, e.getMessage());
            context.saveResult(Map.of(KEY_RUN_ID, runId, "skipped", true));
            return;
        }

        JobAgentEventStream stream = new JobAgentEventStream(context);
        runtime.bindOutputObserver(stream, claim.billing()::markOutput);
        try {
            runtime.resumeFromHitl(runId, userInput, stream, claim);
        } finally {
            runtime.unbindOutputObserver(stream);
            MultiAgentRun latest = runtime.loadRun(runId);
            if (latest != null && MultiAgentConstants.STATUS_PAUSED.equals(latest.getStatus())) {
                claim.billing().finishOnPause();
            } else {
                claim.billing().finish();
            }
        }
        context.saveResult(Map.of(KEY_RUN_ID, runId));
        log.info("多 Agent 后台续跑结束 executionId={} runId={}", context.executionId(), runId);
    }
}
