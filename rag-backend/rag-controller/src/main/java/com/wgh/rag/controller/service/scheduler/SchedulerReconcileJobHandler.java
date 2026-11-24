package com.wgh.rag.controller.service.scheduler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wgh.rag.agent.constant.AgentConstants;
import com.wgh.rag.agent.entity.AgentPlan;
import com.wgh.rag.agent.mapper.AgentPlanMapper;
import com.wgh.rag.agentmulti.constant.MultiAgentConstants;
import com.wgh.rag.agentmulti.entity.MultiAgentRun;
import com.wgh.rag.agentmulti.mapper.MultiAgentRunMapper;
import com.wgh.rag.data.tenant.TenantTransactionTemplate;
import com.wgh.rag.execution.job.JobContext;
import com.wgh.rag.execution.job.JobHandler;
import com.wgh.rag.execution.job.RecurringJob;
import com.wgh.rag.execution.model.ExecutionStatus;
import com.wgh.rag.execution.run.RunTypes;
import com.wgh.rag.scheduler.config.SchedulerProperties;
import com.wgh.rag.scheduler.entity.ExecutionJob;
import com.wgh.rag.scheduler.job.JobPayloadCodec;
import com.wgh.rag.scheduler.job.JobStore;
import com.wgh.rag.scheduler.metrics.SchedulerMetrics;
import com.wgh.rag.scheduler.reconcile.ReconcileReport;
import com.wgh.rag.scheduler.reconcile.ReconcileReportRegistry;
import com.wgh.rag.workflow.constant.WorkflowConstants;
import com.wgh.rag.workflow.entity.WorkflowRun;
import com.wgh.rag.workflow.mapper.WorkflowRunMapper;
import com.wgh.rag.workflow.job.WorkflowRunJobHandler;
import com.wgh.rag.controller.service.resume.ResumeJobTypes;
import com.wgh.rag.controller.service.resume.RunResumeJobHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 作业状态与业务状态对账（{@code SCHEDULER_RECONCILE}，维护车道 + 周期作业）。
 *
 * <p>覆盖两类带明确业务归属的作业：
 * <ul>
 *   <li>{@code WF_RUN}：{@code result_json.runId → wgh_workflow_run.status}；</li>
 *   <li>{@code RUN_RESUME}：{@code payload.runType/runId → wgh_agent_plan / wgh_m_agent_run / wgh_workflow_run}。</li>
 * </ul>
 *
 * <p><b>自动修复边界</b>（只做可证明安全的一类）：
 * 作业仍为 {@code PENDING}、已超过宽限期，且业务记录已终态或不存在 → 取消该排队作业
 * （{@code CANCELLED}，不会进入重试）。其余一律只告警 + 指标，不改业务状态：
 * <ul>
 *   <li>{@code LEASED/RUNNING} 与业务终态不一致：执行体可能正在收尾，交由租约/失联回收处理；</li>
 *   <li>{@code SUCCEEDED} 但业务仍活跃：可能是业务侧收尾未完成，自动重跑会产生副作用；</li>
 *   <li>终态失败但业务仍活跃：由 {@code RUN_RESUME_SCAN} 负责恢复，避免两套机制重复投递。</li>
 * </ul>
 *
 * <p>业务处于 {@code PAUSED/HITL_PENDING/PLANNING} 等等待态时视为「未终态」，不算不一致。
 *
 * <p>仅在调度中心启用时注册：{@code wgh.scheduler.enabled=false} 时作业表不会被调度，
 * 对账本身也不应存在；{@link SchedulerMetrics} 为可选依赖，缺失时跳过打点、保留日志。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "wgh.scheduler", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class SchedulerReconcileJobHandler implements JobHandler, RecurringJob {

    /** 作业类型。 */
    public static final String JOB_TYPE = "SCHEDULER_RECONCILE";

    private static final int MAX_FINDINGS = 50;

    private static final Set<String> TERMINAL_BUSINESS_STATUS = Set.of(
            WorkflowConstants.STATUS_COMPLETED,
            WorkflowConstants.STATUS_FAILED,
            WorkflowConstants.STATUS_CANCELLED);

    /** 人工等待态：既不是终态，也不应当视为「作业与业务不一致」。 */
    private static final Set<String> WAITING_BUSINESS_STATUS = Set.of(
            WorkflowConstants.STATUS_PAUSED,
            WorkflowConstants.STATUS_HITL_PENDING,
            WorkflowConstants.STATUS_DEBUG_PAUSED);

    private static final Set<ExecutionStatus> ACTIVE_JOB_STATUS = Set.of(
            ExecutionStatus.PENDING, ExecutionStatus.LEASED, ExecutionStatus.RUNNING);

    private final SchedulerProperties properties;

    /** 候选扫描与修复写入都收敛到 JobStore（调度角色短事务），处理器不直接碰作业表。 */
    private final JobStore jobStore;

    /** 业务读取按作业归属租户开只读短事务，不依赖线程当前租户。 */
    private final TenantTransactionTemplate transactions;

    private final JobPayloadCodec codec;

    private final WorkflowRunMapper workflowRunMapper;

    private final AgentPlanMapper agentPlanMapper;

    private final MultiAgentRunMapper multiAgentRunMapper;

    /** 调度指标为可选依赖：调度关闭或指标组件缺失时不影响对账与日志。 */
    private final ObjectProvider<SchedulerMetrics> metrics;

    private final ReconcileReportRegistry reportRegistry;

    @Override
    public String jobType() {
        return JOB_TYPE;
    }

    @Override
    public Duration interval() {
        Duration interval = properties.getReconcile().getInterval();
        return interval == null || interval.isZero() || interval.isNegative()
                ? Duration.ofMinutes(10) : interval;
    }

    @Override
    public void execute(JobContext context) {
        if (!properties.getReconcile().isEnabled()) {
            log.debug("作业对账已关闭，跳过本轮");
            return;
        }
        context.cancellation().throwIfCancellationRequested();

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime since = now.minus(properties.getReconcile().getLookback());
        int batch = Math.max(1, properties.getReconcile().getBatchSize());

        int scanned = 0;
        int mismatches = 0;
        int repaired = 0;
        int alertOnly = 0;
        int skipped = 0;
        Map<String, Integer> byType = new LinkedHashMap<>();
        List<ReconcileReport.Finding> findings = new ArrayList<>();
        SchedulerMetrics schedulerMetrics = metrics.getIfAvailable();

        // 三段式：① 调度角色短事务跨租户扫候选 → ② 按作业归属租户逐条只读事务读业务
        // → ③ 调度角色短事务修复。② 与 ③ 分开，读写事务不混用。
        for (String jobType : List.of(WorkflowRunJobHandler.JOB_TYPE, ResumeJobTypes.RUN_RESUME)) {
            context.cancellation().throwIfCancellationRequested();
            List<ExecutionJob> candidates = jobStore.reconcileCandidates(jobType, since, batch);
            for (ExecutionJob job : candidates) {
                context.cancellation().throwIfCancellationRequested();
                scanned++;
                String tenantId = job.getTenantId();
                if (tenantId == null || tenantId.isBlank()) {
                    skipped++;
                    if (schedulerMetrics != null) {
                        schedulerMetrics.recordReconcileSkipped("tenant_missing");
                    }
                    log.warn("对账跳过：作业缺少租户归属 executionId={} jobType={}",
                            job.getExecutionId(), jobType);
                    continue;
                }
                BusinessState business;
                try {
                    business = transactions.execute(tenantId, true, () -> businessState(job));
                } catch (RuntimeException e) {
                    // 业务读失败不判定：既不修复也不报不一致，避免把读失败误判成 business_missing
                    skipped++;
                    if (schedulerMetrics != null) {
                        schedulerMetrics.recordReconcileSkipped("business_read_failed");
                    }
                    log.warn("对账跳过：业务读取失败 tenant={} executionId={} jobType={}",
                            tenantId, job.getExecutionId(), jobType, e);
                    continue;
                }
                ReconcileReport.Finding finding = inspect(job, business, now);
                if (finding == null) {
                    continue;
                }
                mismatches++;
                byType.merge(finding.mismatchType(), 1, Integer::sum);
                if (schedulerMetrics != null) {
                    schedulerMetrics.recordReconcileMismatch(finding.mismatchType());
                }
                if ("AUTO_REPAIRED".equals(finding.action())) {
                    repaired++;
                    if (schedulerMetrics != null) {
                        schedulerMetrics.recordReconcileRepaired("job_cancelled");
                    }
                } else {
                    alertOnly++;
                }
                if (findings.size() < MAX_FINDINGS) {
                    findings.add(finding);
                }
                log.warn("作业/业务状态不一致 executionId={} jobType={} jobStatus={} business={}:{} status={} type={} action={}",
                        finding.executionId(), finding.jobType(), finding.jobStatus(),
                        finding.businessType(), finding.businessId(), finding.businessStatus(),
                        finding.mismatchType(), finding.action());
            }
        }

        ReconcileReport report = new ReconcileReport(now, scanned, mismatches, repaired, alertOnly, skipped,
                Map.copyOf(byType), List.copyOf(findings));
        reportRegistry.record(report);
        log.info("作业对账完成 scanned={} mismatches={} repaired={} alertOnly={} skipped={} byType={}",
                scanned, mismatches, repaired, alertOnly, skipped, byType);
    }

    /**
     * 检查单条作业；返回 {@code null} 表示无法对账或状态一致。
     */
    private ReconcileReport.Finding inspect(ExecutionJob job, BusinessState business, LocalDateTime now) {
        ExecutionStatus jobStatus = job.getStatus();
        if (jobStatus == null) {
            return null;
        }
        if (business == null) {
            // 尚无业务归属（如 WF_RUN 首次执行前还没写入 runId）或未知 runType，无法对账
            return null;
        }

        boolean activeJob = ACTIVE_JOB_STATUS.contains(jobStatus);
        boolean terminalBusiness = business.exists() && TERMINAL_BUSINESS_STATUS.contains(business.status());

        if (terminalBusiness) {
            if (!activeJob) {
                return null;
            }
            if (jobStatus == ExecutionStatus.PENDING && olderThan(job, now, properties.getReconcile().getPendingGrace())) {
                return repair(job, business, "business_terminal_job_active",
                        "业务记录已终态，取消尚未领取的作业");
            }
            return finding(job, business, jobStatus, "business_terminal_job_active", "ALERT_ONLY",
                    "业务已终态但作业仍活跃，交由租约/失联回收处理");
        }

        if (!business.exists()) {
            if (jobStatus == ExecutionStatus.PENDING) {
                if (olderThan(job, now, properties.getReconcile().getPendingGrace())) {
                    return repair(job, business, "business_missing", "业务记录不存在，取消尚未领取的作业");
                }
                return null;
            }
            if (jobStatus == ExecutionStatus.SUCCEEDED || jobStatus == ExecutionStatus.LEASED
                    || jobStatus == ExecutionStatus.RUNNING) {
                return finding(job, business, jobStatus, "business_missing", "ALERT_ONLY",
                        "作业活跃/已成功但业务记录不存在");
            }
            return null;
        }

        // HITL/暂停等等待态：作业结束是正常的，业务等待人工继续，不算不一致
        if (WAITING_BUSINESS_STATUS.contains(business.status())) {
            return null;
        }

        if (jobStatus == ExecutionStatus.SUCCEEDED) {
            return finding(job, business, jobStatus, "business_active_job_succeeded", "ALERT_ONLY",
                    "作业已成功但业务仍非终态");
        }
        if (jobStatus == ExecutionStatus.FAILED || jobStatus == ExecutionStatus.DEAD
                || jobStatus == ExecutionStatus.CANCELLED) {
            return finding(job, business, jobStatus, "business_active_job_terminal", "ALERT_ONLY",
                    "作业已失败/取消但业务仍非终态，由中断续跑扫描恢复");
        }
        return null;
    }

    /** 只取消 PENDING 作业；并发被领取时返回 {@code null}，由下一轮重新判断。 */
    private ReconcileReport.Finding repair(ExecutionJob job, BusinessState business,
                                           String mismatchType, String detail) {
        String reason = "对账：" + detail;
        boolean updated = jobStore.cancelPendingForReconcile(job.getId(), reason);
        if (!updated) {
            log.debug("对账修复竞争失败（作业已被领取或已终态） executionId={}", job.getExecutionId());
            return null;
        }
        return finding(job, business, job.getStatus(), mismatchType, "AUTO_REPAIRED", detail);
    }

    private ReconcileReport.Finding finding(ExecutionJob job, BusinessState business,
                                            ExecutionStatus jobStatus, String mismatchType,
                                            String action, String detail) {
        return new ReconcileReport.Finding(
                job.getExecutionId(),
                job.getJobType(),
                jobStatus == null ? null : jobStatus.name(),
                business.type(),
                business.id(),
                business.status(),
                mismatchType,
                action,
                detail);
    }

    /**
     * 解析作业 → 业务记录；无法定位时返回 {@code null}。
     */
    private BusinessState businessState(ExecutionJob job) {
        if (WorkflowRunJobHandler.JOB_TYPE.equals(job.getJobType())) {
            Map<String, Object> result = parseJson(job.getResultJson());
            Object runId = result.get("runId");
            if (runId == null) {
                return null;
            }
            Long id;
            try {
                id = Long.parseLong(String.valueOf(runId));
            } catch (NumberFormatException e) {
                return new BusinessState("WORKFLOW", String.valueOf(runId), null, false);
            }
            WorkflowRun run = workflowRunMapper.selectById(id);
            return new BusinessState("WORKFLOW", String.valueOf(id),
                    run == null ? null : run.getStatus(), run != null);
        }
        if (ResumeJobTypes.RUN_RESUME.equals(job.getJobType())) {
            Map<String, Object> payload = parseJson(job.getPayloadJson());
            Object runType = payload.get(RunResumeJobHandler.PAYLOAD_RUN_TYPE);
            Object runId = payload.get(RunResumeJobHandler.PAYLOAD_RUN_ID);
            if (runType == null || runId == null) {
                return null;
            }
            String type = String.valueOf(runType);
            String id = String.valueOf(runId);
            return switch (type) {
                case RunTypes.WORKFLOW -> {
                    WorkflowRun run = parseLong(id)
                            .map(workflowRunMapper::selectById).orElse(null);
                    yield new BusinessState(RunTypes.WORKFLOW, id,
                            run == null ? null : run.getStatus(), run != null);
                }
                case RunTypes.AGENT -> {
                    AgentPlan plan = parseLong(id).map(agentPlanMapper::selectById).orElse(null);
                    yield new BusinessState(RunTypes.AGENT, id,
                            plan == null ? null : plan.getStatus(), plan != null);
                }
                case RunTypes.MULTI_AGENT -> {
                    MultiAgentRun run = multiAgentRunMapper.selectList(new LambdaQueryWrapper<MultiAgentRun>()
                                    .eq(MultiAgentRun::getRunId, id)
                                    .last("LIMIT 1"))
                            .stream().findFirst().orElse(null);
                    yield new BusinessState(RunTypes.MULTI_AGENT, id,
                            run == null ? null : run.getStatus(), run != null);
                }
                default -> null;
            };
        }
        return null;
    }

    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return codec.fromJson(json);
        } catch (IllegalArgumentException e) {
            return Map.of();
        }
    }

    private java.util.Optional<Long> parseLong(String value) {
        try {
            return java.util.Optional.of(Long.parseLong(value));
        } catch (NumberFormatException e) {
            return java.util.Optional.empty();
        }
    }

    private boolean olderThan(ExecutionJob job, LocalDateTime now, Duration grace) {
        LocalDateTime reference = job.getUpdateTime() != null ? job.getUpdateTime() : job.getCreateTime();
        if (reference == null || grace == null || grace.isNegative() || grace.isZero()) {
            return true;
        }
        return reference.isBefore(now.minus(grace));
    }

    /**
     * 业务侧状态快照。
     *
     * @param exists 业务记录是否存在；{@code false} 时 {@code status} 为空
     */
    private record BusinessState(String type, String id, String status, boolean exists) {
    }
}
