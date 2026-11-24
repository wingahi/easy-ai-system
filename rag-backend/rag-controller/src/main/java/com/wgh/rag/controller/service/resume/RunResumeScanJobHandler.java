package com.wgh.rag.controller.service.resume;

import com.wgh.rag.controller.service.RunningTaskService;
import com.wgh.rag.data.tenant.PlatformScanTemplate;
import com.wgh.rag.execution.api.ExecutionFacade;
import com.wgh.rag.execution.api.ExecutionJobLookup;
import com.wgh.rag.execution.job.JobContext;
import com.wgh.rag.execution.job.JobHandler;
import com.wgh.rag.execution.job.RecurringJob;
import com.wgh.rag.execution.model.ExecutionLane;
import com.wgh.rag.execution.model.ExecutionRequest;
import com.wgh.rag.execution.run.RunTypes;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 中断任务扫描（{@code RUN_RESUME_SCAN}，维护车道 + 周期作业）。
 *
 * <p>扫描单 Agent / 多 Agent / 工作流三类 RUNNING 且长时间无活动的 run，排除：
 * HITL PAUSED（不在 ACTIVE 状态集合内）、已有活跃 {@code WF_RUN} 作业的 run。
 * 每个 run 以最后活动时间生成 bizKey，跨实例重复扫描由作业表唯一键去重。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RunResumeScanJobHandler implements JobHandler, RecurringJob {

    private final ResumeProperties properties;
    private final RunningTaskService runningTaskService;
    private final ObjectProvider<ExecutionFacade> executionFacade;
    private final ObjectProvider<ExecutionJobLookup> jobLookup;
    private final PlatformScanTemplate platformScan;

    @Override
    public String jobType() {
        return ResumeJobTypes.RUN_RESUME_SCAN;
    }

    @Override
    public Duration interval() {
        return properties.getScanInterval() == null
                ? Duration.ofMinutes(5) : properties.getScanInterval();
    }

    @Override
    public void execute(JobContext context) {
        if (!properties.isEnabled()) {
            log.debug("中断扫描已关闭，跳过本轮");
            return;
        }
        context.cancellation().throwIfCancellationRequested();
        ExecutionFacade facade = executionFacade.getIfAvailable();
        if (facade == null) {
            log.debug("调度中心未启用，跳过中断扫描");
            return;
        }

        AtomicInteger submitted = new AtomicInteger();
        AtomicInteger scanned = new AtomicInteger();
        // 逐租户扫候选：候选查询与投递都必须带上租户，否则只会命中默认租户
        PlatformScanTemplate.Result result = platformScan.scan(false,
                () -> context.cancellation().isCancelled(), tenantId -> {
                    List<RunningTaskService.StaleCandidate> candidates = runningTaskService
                            .listStaleCandidates(tenantId, properties.getStaleMinutes(), properties.getBatchSize());
                    long failed = 0;
                    for (RunningTaskService.StaleCandidate candidate : candidates) {
                        context.cancellation().throwIfCancellationRequested();
                        scanned.incrementAndGet();
                        String runType = mapType(candidate.taskType());
                        if (runType == null) {
                            continue;
                        }
                        // 工作流可能正由 WF_RUN 作业执行，避免扫描与正常执行/重试双跑
                        if (RunTypes.WORKFLOW.equals(runType)) {
                            ExecutionJobLookup lookup = jobLookup.getIfAvailable();
                            if (lookup != null && lookup.hasActiveJobForRun("WF_RUN", candidate.runId())) {
                                continue;
                            }
                        }
                        long epoch = candidate.lastActivity() == null ? 0L
                                : candidate.lastActivity().toEpochSecond(ZoneOffset.UTC);
                        String bizKey = "run-resume:" + runType + ":" + candidate.runId() + ":" + epoch;
                        try {
                            facade.submit(ExecutionRequest
                                    .builder(ResumeJobTypes.RUN_RESUME, ExecutionLane.BACKGROUND)
                                    .tenant(tenantId)
                                    .bizKey(bizKey)
                                    .payload(Map.of(
                                            RunResumeJobHandler.PAYLOAD_RUN_TYPE, runType,
                                            RunResumeJobHandler.PAYLOAD_RUN_ID, candidate.runId(),
                                            "reason", "stale",
                                            "detectedAt", epoch))
                                    .build());
                            submitted.incrementAndGet();
                        } catch (RuntimeException e) {
                            failed++;
                            log.warn("[ALERT] 投递续跑作业失败 tenant={} runType={} runId={}",
                                    tenantId, runType, candidate.runId(), e);
                        }
                    }
                    return new PlatformScanTemplate.TenantCounters(candidates.size(), failed);
                });
        if (submitted.get() > 0 || result.tenantsFailed() > 0) {
            log.info("中断扫描提交续跑作业 {} 条（候选 {} 条，租户 {} 个，失败租户 {} 个）",
                    submitted.get(), scanned.get(), result.tenantsOk(), result.tenantsFailed());
        }
    }

    private String mapType(String taskType) {
        if (RunningTaskService.TYPE_AGENT.equals(taskType)) {
            return RunTypes.AGENT;
        }
        if (RunningTaskService.TYPE_MULTI_AGENT.equals(taskType)) {
            return RunTypes.MULTI_AGENT;
        }
        if (RunningTaskService.TYPE_WORKFLOW.equals(taskType)) {
            return RunTypes.WORKFLOW;
        }
        return null;
    }
}
