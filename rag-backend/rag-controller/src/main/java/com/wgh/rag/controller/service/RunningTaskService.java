package com.wgh.rag.controller.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wgh.rag.agent.constant.AgentConstants;
import com.wgh.rag.agent.entity.AgentPlan;
import com.wgh.rag.agent.entity.AgentStep;
import com.wgh.rag.agent.mapper.AgentPlanMapper;
import com.wgh.rag.agent.mapper.AgentStepMapper;
import com.wgh.rag.agentmulti.entity.MultiAgentRun;
import com.wgh.rag.agentmulti.entity.MultiAgentStep;
import com.wgh.rag.agentmulti.mapper.MultiAgentRunMapper;
import com.wgh.rag.agentmulti.mapper.MultiAgentStepMapper;
import com.wgh.rag.data.tenant.TenantTransactionTemplate;
import com.wgh.rag.execution.api.InteractiveRunRegistry;
import com.wgh.rag.execution.model.ExecutionLane;
import com.wgh.rag.scheduler.entity.ExecutionJob;
import com.wgh.rag.scheduler.job.JobStore;
import com.wgh.rag.scheduler.mapper.ExecutionJobMapper;
import com.wgh.rag.workflow.constant.WorkflowConstants;
import com.wgh.rag.workflow.entity.Workflow;
import com.wgh.rag.workflow.entity.WorkflowNodeRun;
import com.wgh.rag.workflow.entity.WorkflowRun;
import com.wgh.rag.workflow.mapper.WorkflowMapper;
import com.wgh.rag.workflow.mapper.WorkflowNodeRunMapper;
import com.wgh.rag.workflow.mapper.WorkflowRunMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 运行中任务查询（只读）。
 * <p>
 * 覆盖单 Agent（wgh_agent_plan/step）、多 Agent（wgh_m_agent_run/step）、工作流（wgh_workflow_run/node_run），
 * 以及普通对话（wgh_execution_job 中 lane=INTERACTIVE 的 CHAT 登记行），
 * 按最后活动时间倒序合并分页，供管理端运维观测。未传 type 时仍只合并前三类，CHAT 仅在显式指定时查询。
 * 不再提供任何用户触发的继续执行入口，中断任务的处置交由调度中心的异步执行点负责。
 */
@Service
@RequiredArgsConstructor
public class RunningTaskService {

    public static final String TYPE_AGENT = "AGENT";
    public static final String TYPE_MULTI_AGENT = "MULTI_AGENT";
    public static final String TYPE_WORKFLOW = "WORKFLOW";
    public static final String TYPE_CHAT = "CHAT";

    private static final Set<String> ACTIVE_STATUSES = Set.of(
            AgentConstants.STATUS_RUNNING, "PLANNING");

    private final AgentPlanMapper agentPlanMapper;
    private final AgentStepMapper agentStepMapper;

    private final WorkflowRunMapper workflowRunMapper;
    private final WorkflowNodeRunMapper workflowNodeRunMapper;
    private final WorkflowMapper workflowMapper;

    private final MultiAgentRunMapper multiAgentRunMapper;
    private final MultiAgentStepMapper multiAgentStepMapper;

    /** 普通对话运行态：调度中心作业表中的 INTERACTIVE 登记行。 */
    private final ExecutionJobMapper executionJobMapper;
    private final JobStore jobStore;

    /** 指定租户查询时显式下发 {@code app.tenant_id}。 */
    private final TenantTransactionTemplate transactions;

    @Transactional(readOnly = true)
    public Map<String, Object> listRunningTasks(String type, String status, Long page, Long size) {
        String normalizedType = normalizeType(type);
        if (type != null && !type.isBlank() && normalizedType == null) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("total", 0L);
            empty.put("items", List.of());
            return empty;
        }
        String normalizedStatus = normalizeStatus(status);
        Set<String> statuses = normalizedStatus == null ? ACTIVE_STATUSES : Set.of(normalizedStatus);

        List<TaskView> views = new ArrayList<>();
        if (normalizedType == null || TYPE_AGENT.equals(normalizedType)) {
            views.addAll(agentViews(statuses));
        }
        if (normalizedType == null || TYPE_MULTI_AGENT.equals(normalizedType)) {
            views.addAll(multiAgentViews(statuses));
        }
        if (normalizedType == null || TYPE_WORKFLOW.equals(normalizedType)) {
            views.addAll(workflowViews(statuses));
        }
        // CHAT 只在显式指定类型时查询：未传 type 时保持原有三源合并口径
        if (TYPE_CHAT.equals(normalizedType)) {
            views.addAll(chatViews(statuses));
        }

        views.sort(Comparator.comparingLong(TaskView::stayMinutes).reversed()
                .thenComparing(TaskView::startTime, Comparator.nullsLast(Comparator.naturalOrder())));

        long total = views.size();
        long p = page == null || page < 1 ? 1 : page;
        long s = size == null || size < 1 ? 20 : size;
        int from = (int) Math.min((p - 1) * s, total);
        int to = (int) Math.min(from + s, total);
        List<Map<String, Object>> items = views.subList(from, to).stream()
                .map(TaskView::toMap).collect(Collectors.toList());

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("total", total);
        resp.put("items", items);
        return resp;
    }

    /**
     * 中断候选：三类 run 中仍为 RUNNING/PLANNING、且最后活动时间超过阈值的记录。
     *
     * <p>只做筛选，不判断是否已有活跃作业；调用方（中断扫描作业）再排除 WF_RUN 等正常执行中的 run。
     */
    public List<StaleCandidate> listStaleCandidates(long staleMinutes, int limit) {
        if (staleMinutes <= 0 || limit <= 0) {
            return List.of();
        }
        return doListStaleCandidates(staleMinutes, limit);
    }

    /**
     * 指定租户的中断候选（平台级扫描逐租户调用）。
     *
     * <p>必须在租户只读事务内查询：切换 wgh_app 后缺少 {@code app.tenant_id} 时 RLS 会静默返回 0 行。
     */
    public List<StaleCandidate> listStaleCandidates(String tenantId, long staleMinutes, int limit) {
        if (tenantId == null || tenantId.isBlank() || staleMinutes <= 0 || limit <= 0) {
            return List.of();
        }
        return transactions.execute(tenantId, true, () -> doListStaleCandidates(staleMinutes, limit));
    }

    private List<StaleCandidate> doListStaleCandidates(long staleMinutes, int limit) {
        List<TaskView> views = new ArrayList<>();
        views.addAll(agentViews(ACTIVE_STATUSES));
        views.addAll(multiAgentViews(ACTIVE_STATUSES));
        views.addAll(workflowViews(ACTIVE_STATUSES));
        return views.stream()
                .filter(v -> v.stayMinutes() >= staleMinutes)
                .sorted(Comparator.comparingLong(TaskView::stayMinutes).reversed())
                .limit(limit)
                .map(v -> new StaleCandidate(v.taskType(), String.valueOf(v.taskId()), v.status(),
                        v.lastActivity()))
                .toList();
    }

    /** 中断候选。{@code taskType} 与页面类型一致（AGENT / MULTI_AGENT / WORKFLOW）。 */
    public record StaleCandidate(String taskType, String runId, String status, LocalDateTime lastActivity) {
    }

    // ==================== 单 Agent ====================

    private List<TaskView> agentViews(Set<String> statuses) {
        List<AgentPlan> plans = agentPlanMapper.selectList(new LambdaQueryWrapper<AgentPlan>()
                .in(AgentPlan::getStatus, statuses));
        if (plans.isEmpty()) return List.of();

        List<Long> planIds = plans.stream().map(AgentPlan::getId).toList();
        Map<Long, List<AgentStep>> stepsByPlan = agentStepMapper.selectList(new LambdaQueryWrapper<AgentStep>()
                        .in(AgentStep::getPlanId, planIds)
                        .orderByAsc(AgentStep::getStepIndex))
                .stream().collect(Collectors.groupingBy(AgentStep::getPlanId));

        LocalDateTime now = LocalDateTime.now();
        List<TaskView> views = new ArrayList<>(plans.size());
        for (AgentPlan plan : plans) {
            List<AgentStep> steps = stepsByPlan.getOrDefault(plan.getId(), List.of());
            LocalDateTime lastActivity = latestActivity(plan.getUpdateTime(), plan.getCreateTime(), stepActivity(steps));
            views.add(new TaskView(TYPE_AGENT, plan.getId(), plan.getUserGoal(), plan.getCreateBy(),
                    plan.getStatus(), agentCurrentNode(steps), plan.getCreateTime(), lastActivity,
                    minutesBetween(lastActivity, now), lastStepStatus(steps)));
        }
        return views;
    }

    private String agentCurrentNode(List<AgentStep> steps) {
        if (steps == null || steps.isEmpty()) return null;
        AgentStep target = steps.stream()
                .filter(s -> AgentConstants.STATUS_RUNNING.equals(s.getStatus()))
                .findFirst()
                .orElseGet(() -> steps.stream()
                        .filter(s -> AgentConstants.STATUS_PENDING.equals(s.getStatus()))
                        .findFirst()
                        .orElse(steps.get(steps.size() - 1)));
        return agentStepLabel(target);
    }

    private String agentStepLabel(AgentStep step) {
        String prefix = "步骤 " + (step.getStepIndex() != null ? step.getStepIndex() : 0);
        return step.getTitle() == null || step.getTitle().isBlank()
                ? prefix : prefix + "：" + step.getTitle();
    }

    // ==================== 工作流 ====================

    private List<TaskView> workflowViews(Set<String> statuses) {
        List<WorkflowRun> runs = workflowRunMapper.selectList(new LambdaQueryWrapper<WorkflowRun>()
                .in(WorkflowRun::getStatus, statuses));
        if (runs.isEmpty()) return List.of();

        List<Long> runIds = runs.stream().map(WorkflowRun::getId).toList();
        Map<Long, List<WorkflowNodeRun>> nodesByRun = workflowNodeRunMapper.selectList(
                        new LambdaQueryWrapper<WorkflowNodeRun>().in(WorkflowNodeRun::getRunId, runIds)
                                .orderByAsc(WorkflowNodeRun::getId))
                .stream().collect(Collectors.groupingBy(WorkflowNodeRun::getRunId));
        Set<Long> workflowIds = runs.stream().map(WorkflowRun::getWorkflowId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> workflowNames = workflowIds.isEmpty() ? Map.of()
                : workflowMapper.selectBatchIds(workflowIds).stream()
                .collect(Collectors.toMap(Workflow::getId, Workflow::getName, (a, b) -> a));

        LocalDateTime now = LocalDateTime.now();
        List<TaskView> views = new ArrayList<>(runs.size());
        for (WorkflowRun run : runs) {
            List<WorkflowNodeRun> nodes = nodesByRun.getOrDefault(run.getId(), List.of());
            LocalDateTime lastActivity = latestActivity(run.getUpdateTime(), run.getStartTime(), nodeActivity(nodes));
            views.add(new TaskView(TYPE_WORKFLOW, run.getId(), workflowNames.get(run.getWorkflowId()),
                    run.getCreateBy(), run.getStatus(), workflowCurrentNode(nodes), run.getStartTime(), lastActivity,
                    minutesBetween(lastActivity, now), lastNodeStatus(nodes)));
        }
        return views;
    }

    private String workflowCurrentNode(List<WorkflowNodeRun> nodes) {
        if (nodes == null || nodes.isEmpty()) return null;
        WorkflowNodeRun running = nodes.stream()
                .filter(n -> WorkflowConstants.STATUS_RUNNING.equals(n.getStatus()))
                .findFirst().orElse(null);
        WorkflowNodeRun target = running != null ? running : nodes.get(nodes.size() - 1);
        return nodeLabel(target);
    }

    private String nodeLabel(WorkflowNodeRun node) {
        if (node == null) return null;
        return node.getNodeName() == null || node.getNodeName().isBlank()
                ? node.getNodeId() : node.getNodeName();
    }

    private String lastNodeStatus(List<WorkflowNodeRun> nodes) {
        if (nodes == null || nodes.isEmpty()) return null;
        return nodes.get(nodes.size() - 1).getStatus();
    }

    // ==================== 多 Agent ====================

    private List<TaskView> multiAgentViews(Set<String> statuses) {
        List<MultiAgentRun> runs = multiAgentRunMapper.selectList(new LambdaQueryWrapper<MultiAgentRun>()
                .in(MultiAgentRun::getStatus, statuses));
        if (runs.isEmpty()) return List.of();

        List<String> runIds = runs.stream().map(MultiAgentRun::getRunId).toList();
        Map<String, List<MultiAgentStep>> stepsByRun = multiAgentStepMapper.selectList(
                        new LambdaQueryWrapper<MultiAgentStep>().in(MultiAgentStep::getRunId, runIds)
                                .orderByAsc(MultiAgentStep::getStepIndex))
                .stream().collect(Collectors.groupingBy(MultiAgentStep::getRunId));

        LocalDateTime now = LocalDateTime.now();
        List<TaskView> views = new ArrayList<>(runs.size());
        for (MultiAgentRun run : runs) {
            List<MultiAgentStep> steps = stepsByRun.getOrDefault(run.getRunId(), List.of());
            LocalDateTime startTime = run.getStartTime() != null ? run.getStartTime() : run.getCreateTime();
            LocalDateTime lastActivity = latestActivity(run.getUpdateTime(), startTime, multiStepActivity(steps));
            views.add(new TaskView(TYPE_MULTI_AGENT, run.getRunId(), run.getUserGoal(), run.getCreateBy(),
                    run.getStatus(), multiAgentCurrentNode(run, steps), startTime, lastActivity,
                    minutesBetween(lastActivity, now), lastMultiStepStatus(steps)));
        }
        return views;
    }

    private String multiAgentCurrentNode(MultiAgentRun run, List<MultiAgentStep> steps) {
        if (steps != null && !steps.isEmpty()) {
            MultiAgentStep latest = steps.get(steps.size() - 1);
            String label = latest.getRole() != null && !latest.getRole().isBlank()
                    ? latest.getRole() : latest.getAgentId();
            if (label != null && !label.isBlank()) return label;
        }
        return run.getCurrentRound() != null ? "第 " + run.getCurrentRound() + " 轮" : null;
    }

    private String lastMultiStepStatus(List<MultiAgentStep> steps) {
        if (steps == null || steps.isEmpty()) return null;
        return steps.get(steps.size() - 1).getStatus();
    }

    // ==================== 普通对话 ====================

    /**
     * 普通对话运行态：来自调度中心的交互式作业登记行。
     *
     * <p>对话仍在请求线程/统一执行器内生成，登记行只服务「进行中」查询，结束即删除；
     * 崩溃残留由租约/实例失联回收为终态，并按保留期清理。</p>
     */
    private List<TaskView> chatViews(Set<String> statuses) {
        List<ExecutionJob> jobs = executionJobMapper.selectList(new LambdaQueryWrapper<ExecutionJob>()
                .eq(ExecutionJob::getLane, ExecutionLane.INTERACTIVE)
                .eq(ExecutionJob::getJobType, InteractiveRunRegistry.RUN_TYPE_CHAT)
                .in(ExecutionJob::getStatus, statuses));
        if (jobs.isEmpty()) return List.of();

        LocalDateTime now = LocalDateTime.now();
        List<TaskView> views = new ArrayList<>(jobs.size());
        for (ExecutionJob job : jobs) {
            Map<String, Object> payload = chatPayload(job);
            Object sessionId = payload.get("sessionId");
            Object title = payload.get("title");
            LocalDateTime startTime = job.getStartTime() != null ? job.getStartTime() : job.getCreateTime();
            LocalDateTime lastActivity = latestActivity(job.getHeartbeatTime(), job.getUpdateTime(), startTime);
            views.add(new TaskView(TYPE_CHAT,
                    sessionId != null ? sessionId : job.getExecutionId(),
                    title instanceof String text ? text : null,
                    job.getOwnerUserId(),
                    job.getStatus() == null ? null : job.getStatus().name(),
                    "对话生成中",
                    startTime,
                    lastActivity,
                    minutesBetween(lastActivity, now),
                    null));
        }
        return views;
    }

    private Map<String, Object> chatPayload(ExecutionJob job) {
        try {
            return jobStore.payloadOf(job).data();
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    // ==================== 公共：类型归一 / 时间 ====================

    private String normalizeType(String type) {
        if (type == null || type.isBlank()) return null;
        String t = type.trim().toUpperCase();
        return switch (t) {
            case TYPE_AGENT, TYPE_MULTI_AGENT, TYPE_WORKFLOW, TYPE_CHAT -> t;
            default -> null;
        };
    }

    private String normalizeStatus(String status) {
        return status == null || status.isBlank() ? null : status.trim().toUpperCase();
    }

    private LocalDateTime latestActivity(LocalDateTime a, LocalDateTime b, LocalDateTime c) {
        LocalDateTime latest = null;
        for (LocalDateTime t : new LocalDateTime[]{a, b, c}) {
            if (t != null && (latest == null || t.isAfter(latest))) latest = t;
        }
        return latest;
    }

    private LocalDateTime stepActivity(List<AgentStep> steps) {
        LocalDateTime latest = null;
        if (steps != null) {
            for (AgentStep s : steps) {
                latest = latestActivity(latest, s.getStartTime(), s.getEndTime());
            }
        }
        return latest;
    }

    private LocalDateTime nodeActivity(List<WorkflowNodeRun> nodes) {
        LocalDateTime latest = null;
        if (nodes != null) {
            for (WorkflowNodeRun n : nodes) {
                latest = latestActivity(latest, n.getStartTime(), n.getEndTime());
            }
        }
        return latest;
    }

    private LocalDateTime multiStepActivity(List<MultiAgentStep> steps) {
        LocalDateTime latest = null;
        if (steps != null) {
            for (MultiAgentStep s : steps) {
                latest = latestActivity(latest, s.getStartTime(), s.getEndTime());
            }
        }
        return latest;
    }

    private String lastStepStatus(List<AgentStep> steps) {
        if (steps == null || steps.isEmpty()) return null;
        return steps.get(steps.size() - 1).getStatus();
    }

    private long minutesBetween(LocalDateTime from, LocalDateTime to) {
        if (from == null || to == null) return 0L;
        return Math.max(0L, Duration.between(from, to).toMinutes());
    }

    private record TaskView(String taskType, Object taskId, String title, String ownerUsername,
                            String status, String currentNode, LocalDateTime startTime,
                            LocalDateTime lastActivity, long stayMinutes, String lastStepStatus) {
        private Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("taskType", taskType);
            m.put("taskId", taskId);
            m.put("title", title);
            m.put("ownerUsername", ownerUsername);
            m.put("status", status);
            m.put("currentNode", currentNode);
            m.put("startTime", startTime);
            m.put("stayMinutes", stayMinutes);
            m.put("lastStepStatus", lastStepStatus);
            return m;
        }
    }
}
