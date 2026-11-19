package com.wgh.rag.controller.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.agent.config.AgentProperties;
import com.wgh.rag.agent.constant.AgentConstants;
import com.wgh.rag.agent.dto.AgentStepResult;
import com.wgh.rag.agent.entity.AgentPlan;
import com.wgh.rag.agent.entity.AgentStep;
import com.wgh.rag.agent.mapper.AgentPlanMapper;
import com.wgh.rag.agent.mapper.AgentStepMapper;
import com.wgh.rag.agent.service.AgentOrchestrator;
import com.wgh.rag.agent.service.PlanLifecycleService;
import com.wgh.rag.agentmulti.constant.MultiAgentConstants;
import com.wgh.rag.agentmulti.entity.MultiAgentRun;
import com.wgh.rag.agentmulti.entity.MultiAgentStep;
import com.wgh.rag.agentmulti.mapper.MultiAgentRunMapper;
import com.wgh.rag.agentmulti.mapper.MultiAgentStepMapper;
import com.wgh.rag.agentmulti.runtime.MultiAgentRuntime;
import com.wgh.rag.agentmulti.service.MultiAgentService;
import com.wgh.rag.chat.entity.ChatSession;
import com.wgh.rag.chat.entity.ChatWindow;
import com.wgh.rag.chat.mapper.ChatSessionMapper;
import com.wgh.rag.chat.service.ChatWindowService;
import com.wgh.rag.chat.service.RagChatService;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.log.MdcContext;
import com.wgh.rag.common.metrics.RagMetrics;
import com.wgh.rag.common.result.ResultCode;
import com.wgh.rag.common.security.SecurityUtils;
import com.wgh.rag.config.redis.RedisDistributedLock;
import com.wgh.rag.config.redis.RedisDistributedLock.LockHandle;
import com.wgh.rag.config.redis.RedisUnavailableException;
import com.wgh.rag.llm.entity.LlmModel;
import com.wgh.rag.llm.service.ModelRouter;
import com.wgh.rag.log.constant.AuditConstants;
import com.wgh.rag.log.entity.AuditLog;
import com.wgh.rag.log.service.AuditLogService;
import com.wgh.rag.workflow.constant.WorkflowConstants;
import com.wgh.rag.workflow.dto.GraphEdge;
import com.wgh.rag.workflow.dto.GraphNode;
import com.wgh.rag.workflow.dto.RunContext;
import com.wgh.rag.workflow.dto.WorkflowGraph;
import com.wgh.rag.workflow.engine.GraphValidator;
import com.wgh.rag.workflow.engine.WorkflowEngine;
import com.wgh.rag.workflow.entity.Workflow;
import com.wgh.rag.workflow.entity.WorkflowNodeRun;
import com.wgh.rag.workflow.entity.WorkflowRun;
import com.wgh.rag.workflow.mapper.WorkflowMapper;
import com.wgh.rag.workflow.mapper.WorkflowNodeRunMapper;
import com.wgh.rag.workflow.mapper.WorkflowRunMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/**
 * 未完成任务查询与继续执行。
 * <p>
 * 覆盖单 Agent（wgh_agent_plan/step）、多 Agent（wgh_m_agent_run/step）、工作流（wgh_workflow_run/node_run）。
 * 恢复动作只做“从最后一个成功步骤之后继续”，复用各模块既有执行入口，异步派发，不在 HTTP 线程内同步执行。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskRecoveryService {

    public static final String TYPE_AGENT = "AGENT";
    public static final String TYPE_MULTI_AGENT = "MULTI_AGENT";
    public static final String TYPE_WORKFLOW = "WORKFLOW";
    public static final String TYPE_CHAT = "CHAT";

    private static final Set<String> ACTIVE_STATUSES = Set.of(
            AgentConstants.STATUS_RUNNING, "PLANNING");

    private final AgentPlanMapper agentPlanMapper;
    private final AgentStepMapper agentStepMapper;
    private final AgentOrchestrator agentOrchestrator;
    private final PlanLifecycleService planLifecycleService;
    private final AgentProperties agentProperties;
    private final ModelRouter modelRouter;
    private final ChatWindowService chatWindowService;

    private final WorkflowRunMapper workflowRunMapper;
    private final WorkflowNodeRunMapper workflowNodeRunMapper;
    private final WorkflowMapper workflowMapper;
    private final WorkflowEngine workflowEngine;
    private final GraphValidator graphValidator;

    private final MultiAgentRunMapper multiAgentRunMapper;
    private final MultiAgentStepMapper multiAgentStepMapper;
    private final MultiAgentService multiAgentService;
    private final MultiAgentRuntime multiAgentRuntime;

    private final ChatSessionMapper chatSessionMapper;
    private final RagChatService ragChatService;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;
    private final RedisDistributedLock redisDistributedLock;
    private final RagMetrics metrics;

    private final AtomicInteger threadSeq = new AtomicInteger();

    /** 进程内恢复互斥：仅用于同实例内的快速失败，不承担跨实例互斥职责。 */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    /** 拒绝恢复的告警去重：仅在状态切换时记录一次 WARN 与降级指标，避免故障期间按请求刷屏。 */
    private final AtomicBoolean fallbackDisabledWarned = new AtomicBoolean(false);
    private final AtomicBoolean fallbackErrorWarned = new AtomicBoolean(false);

    private final ExecutorService resumeExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "task-recovery-" + threadSeq.incrementAndGet());
        t.setDaemon(true);
        return t;
    });

    private final ScheduledExecutorService lockRenewer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "task-recovery-lock-renew");
        t.setDaemon(true);
        return t;
    });

    // ==================== 会话级：未完成任务 / 继续执行 ====================

    /** 查询会话下最近一条可续跑的活跃任务。 */
    public Map<String, Object> getPendingTask(Long sessionId) {
        assertSessionOwned(sessionId);
        TaskCandidate candidate = latestPendingCandidate(sessionId);

        Map<String, Object> resp = new LinkedHashMap<>();
        if (candidate == null) {
            resp.put("hasPending", false);
            resp.put("taskType", null);
            resp.put("taskId", null);
            resp.put("status", null);
            resp.put("startTime", null);
            resp.put("stayMinutes", null);
            return resp;
        }
        resp.put("hasPending", true);
        resp.put("taskType", candidate.taskType());
        resp.put("taskId", candidate.taskId());
        resp.put("status", candidate.status());
        resp.put("startTime", candidate.startTime());
        resp.put("stayMinutes", minutesSince(candidate.lastActivity()));
        return resp;
    }

    /** 会话级继续执行：先定位待续跑任务，再走统一恢复入口。 */
    public Map<String, Object> resumeSessionTask(Long sessionId) {
        assertSessionOwned(sessionId);
        TaskCandidate candidate = latestPendingCandidate(sessionId);
        if (candidate == null) {
            return resumeResult(false, "当前会话没有可继续执行的任务", null);
        }
        return resumeTask(candidate.taskType(), String.valueOf(candidate.taskId()));
    }

    // ==================== 管理端：运行中任务列表 / 继续执行 ====================

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

    /** 管理端继续执行：按 taskType 分发。 */
    public Map<String, Object> resumeTask(String taskType, String taskId) {
        if (taskType == null || taskType.isBlank()) {
            return resumeResult(false, "taskType 不能为空", null);
        }
        String type = normalizeType(taskType);
        if (type == null) {
            return resumeResult(false, "不支持的任务类型：" + taskType, null);
        }
        if (taskId == null || taskId.isBlank()) {
            return resumeResult(false, "taskId 不能为空", null);
        }
        String operator = SecurityUtils.currentUsername();
        return switch (type) {
            case TYPE_AGENT -> resumeAgent(taskId, operator);
            case TYPE_MULTI_AGENT -> resumeMultiAgent(taskId, operator);
            case TYPE_WORKFLOW -> resumeWorkflow(taskId, operator);
            default -> resumeResult(false, "普通对话暂无可恢复的持久化任务", null);
        };
    }

    // ==================== 单 Agent ====================

    private Map<String, Object> resumeAgent(String taskId, String operator) {
        Long planId = parseLongOrNull(taskId);
        if (planId == null) return resumeResult(false, "AGENT 任务 taskId 必须为数字", null);
        AgentPlan plan = agentPlanMapper.selectById(planId);
        if (plan == null) return resumeResult(false, "Agent 计划不存在", null);

        LockAttempt attempt = tryAcquireRecoveryLock(TYPE_AGENT, String.valueOf(planId));
        if (!attempt.acquired()) return resumeResult(false, attempt.rejectReason(), null);
        RecoveryLock lock = attempt.lock();

        boolean async = false;
        try {
            if (AgentConstants.STATUS_PAUSED.equals(plan.getStatus())) {
                return resumeResult(false, "任务处于 HITL 暂停，请使用人工确认入口", null);
            }
            if (!AgentConstants.STATUS_RUNNING.equals(plan.getStatus())) {
                return resumeResult(false, "任务已结束（" + plan.getStatus() + "），无需继续执行", null);
            }
            List<AgentStep> steps = loadAgentSteps(planId);
            if (steps.stream().anyMatch(s -> AgentConstants.STATUS_HITL_PENDING.equals(s.getStatus()))) {
                return resumeResult(false, "任务等待人工确认，请使用 HITL 确认入口", null);
            }

            List<AgentStep> remaining = steps.stream()
                    .filter(s -> AgentConstants.STATUS_PENDING.equals(s.getStatus())
                            || AgentConstants.STATUS_RUNNING.equals(s.getStatus()))
                    .sorted(Comparator.comparing(AgentStep::getStepIndex,
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .toList();
            if (remaining.isEmpty()) {
                planLifecycleService.markPlanCompleteTx(plan, AgentConstants.STATUS_COMPLETED);
                String msg = "任务步骤已全部完成，已标记为完成";
                audit(TYPE_AGENT, String.valueOf(planId), operator, "NONE");
                return resumeResult(true, msg, "NONE");
            }

            AgentStep first = remaining.get(0);
            String resumedFrom = agentStepLabel(first);
            WindowChains chains = resolveAgentChains(plan);
            long sessionId = plan.getSessionId() == null ? 0L : plan.getSessionId();

            async = dispatch(lock, () -> {
                try {
                    AgentStepResult result = agentOrchestrator.resumeAfterInterruption(
                            planId,
                            agentProperties.getDefaultReflectionThreshold(),
                            agentProperties.getDefaultReflectionRetries(),
                            null, chains.executor(), chains.planner());
                    if (result != null && result.getFinalText() != null && !result.getFinalText().isBlank()) {
                        ragChatService.appendAssistantMessage(sessionId, result.getFinalText());
                    }
                } catch (Exception e) {
                    log.error("Agent 计划续跑失败 planId={}", planId, e);
                }
            });
            if (!async) return resumeResult(false, "恢复执行器繁忙，请稍后重试", null);

            audit(TYPE_AGENT, String.valueOf(planId), operator, resumedFrom);
            return resumeResult(true, "已触发继续执行", resumedFrom);
        } finally {
            if (!async) releaseRecoveryLock(lock);
        }
    }

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
                    plan.getStatus(), agentCurrentNode(steps), plan.getCreateTime(),
                    minutesBetween(lastActivity, now), lastStepStatus(steps)));
        }
        return views;
    }

    private TaskCandidate latestAgentCandidate(Long sessionId) {
        AgentPlan plan = agentPlanMapper.selectOne(new LambdaQueryWrapper<AgentPlan>()
                .eq(AgentPlan::getSessionId, sessionId)
                .in(AgentPlan::getStatus, ACTIVE_STATUSES)
                .orderByDesc(AgentPlan::getId)
                .last("LIMIT 1"));
        if (plan == null) return null;
        List<AgentStep> steps = loadAgentSteps(plan.getId());
        LocalDateTime lastActivity = latestActivity(plan.getUpdateTime(), plan.getCreateTime(), stepActivity(steps));
        return new TaskCandidate(TYPE_AGENT, plan.getId(), plan.getStatus(), plan.getCreateTime(), lastActivity);
    }

    private List<AgentStep> loadAgentSteps(Long planId) {
        return agentStepMapper.selectList(new LambdaQueryWrapper<AgentStep>()
                .eq(AgentStep::getPlanId, planId)
                .orderByAsc(AgentStep::getStepIndex));
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

    private WindowChains resolveAgentChains(AgentPlan plan) {
        ChatWindow window = plan == null ? null : chatWindowService.findById(plan.getChatWindowId());
        Long executorModelId = window != null && window.getAgentExecutorModelId() != null
                ? window.getAgentExecutorModelId()
                : (window != null && window.getLlmModelId() != null ? window.getLlmModelId() : plan.getLlmModelId());
        Long plannerModelId = window != null && window.getAgentPlannerModelId() != null
                ? window.getAgentPlannerModelId() : executorModelId;
        String routeGroup = window != null ? window.getModelRouteGroup() : null;
        return new WindowChains(
                modelRouter.resolveChain(routeGroup, executorModelId),
                modelRouter.resolveChain(routeGroup, plannerModelId));
    }

    // ==================== 工作流 ====================

    private Map<String, Object> resumeWorkflow(String taskId, String operator) {
        Long runId = parseLongOrNull(taskId);
        if (runId == null) return resumeResult(false, "WORKFLOW 任务 taskId 必须为数字", null);
        WorkflowRun run = workflowRunMapper.selectById(runId);
        if (run == null) return resumeResult(false, "工作流运行记录不存在", null);

        LockAttempt attempt = tryAcquireRecoveryLock(TYPE_WORKFLOW, String.valueOf(runId));
        if (!attempt.acquired()) return resumeResult(false, attempt.rejectReason(), null);
        RecoveryLock lock = attempt.lock();

        boolean async = false;
        try {
            if (WorkflowConstants.STATUS_PAUSED.equals(run.getStatus())) {
                return resumeResult(false, "工作流处于 HITL 暂停，请使用人工确认入口", null);
            }
            if (!WorkflowConstants.STATUS_RUNNING.equals(run.getStatus())) {
                return resumeResult(false, "任务已结束（" + run.getStatus() + "），无需继续执行", null);
            }

            WorkflowGraph graph;
            GraphValidator.GraphValidationResult validation;
            try {
                graph = objectMapper.readValue(run.getGraphSnapshot(), WorkflowGraph.class);
                validation = graphValidator.validate(graph);
            } catch (Exception e) {
                return resumeResult(false, "工作流图快照解析失败：" + e.getMessage(), null);
            }
            if (validation.hasErrors()) {
                return resumeResult(false, "工作流图校验失败：" + String.join("；", validation.errors), null);
            }

            RunContext ctx = workflowEngine.parseContext(run.getContextJson());
            ctx.getVars().putIfAbsent("input", run.getInputText() == null ? "" : run.getInputText());
            ctx.getVars().putIfAbsent("lastOutput", "");

            Checkpoint checkpoint = buildCheckpoint(run, graph, ctx);
            if (checkpoint.readyNodes().isEmpty()) {
                if (checkpoint.allCompleted()) {
                    markWorkflowCompleted(run, ctx);
                    audit(TYPE_WORKFLOW, String.valueOf(runId), operator, "NONE");
                    return resumeResult(true, "工作流节点已全部完成，已标记为完成", "NONE");
                }
                return resumeResult(false, "无法从检查点恢复：没有就绪节点", null);
            }

            ctx.setPendingNodeIds(checkpoint.readyNodes());
            GraphValidator.GraphValidationResult adjusted = new GraphValidator.GraphValidationResult(
                    validation.errors, validation.backEdgeSources, checkpoint.adjustedInDegree(),
                    validation.rawInDegree, graph);
            String resumedFrom = describeNode(graph, checkpoint.readyNodes().get(0));
            BiConsumer<String, Map<String, Object>> noopSink = (event, payload) -> { };

            async = dispatch(lock, () -> {
                try {
                    workflowEngine.resume(run, graph, adjusted, ctx, noopSink);
                } catch (Exception e) {
                    log.error("工作流续跑失败 runId={}", runId, e);
                    markWorkflowFailed(runId, e.getMessage());
                }
            });
            if (!async) return resumeResult(false, "恢复执行器繁忙，请稍后重试", null);

            audit(TYPE_WORKFLOW, String.valueOf(runId), operator, resumedFrom);
            return resumeResult(true, "已触发继续执行", resumedFrom);
        } finally {
            if (!async) releaseRecoveryLock(lock);
        }
    }

    /**
     * 基于 wgh_workflow_node_run 的真实轨迹重建检查点：
     * <ul>
     *   <li>已完成 = SUCCESS，或 FAILED 但 context 已有输出（onError=CONTINUE）；</li>
     *   <li>用 node_run.output_json 回填 nodeOutputs/lastOutput/loopIterations，修复“node_run 成功但 context 未落库”的窗口；</li>
     *   <li>按 CONDITION 的 result / LOOP 的 handle 只沿实际选中的边推进，未走到的分支永不进入 ready。</li>
     * </ul>
     */
    private Checkpoint buildCheckpoint(WorkflowRun run, WorkflowGraph graph, RunContext ctx) {
        List<WorkflowNodeRun> nodeRuns = workflowNodeRunMapper.selectList(new LambdaQueryWrapper<WorkflowNodeRun>()
                .eq(WorkflowNodeRun::getRunId, run.getId())
                .orderByAsc(WorkflowNodeRun::getId));

        Map<String, WorkflowNodeRun> latestByNode = new LinkedHashMap<>();
        for (WorkflowNodeRun nr : nodeRuns) latestByNode.put(nr.getNodeId(), nr);

        Map<String, GraphNode> nodeMap = graph.getNodes().stream()
                .collect(Collectors.toMap(GraphNode::getId, n -> n, (a, b) -> a));

        Set<String> completed = new HashSet<>();
        Map<String, Map<String, Object>> outputs = new HashMap<>();
        String latestOutputText = null;
        int latestLoopIteration = 0;

        for (WorkflowNodeRun nr : latestByNode.values()) {
            boolean success = WorkflowConstants.STATUS_SUCCESS.equals(nr.getStatus());
            boolean continuedFailure = WorkflowConstants.STATUS_FAILED.equals(nr.getStatus())
                    && ctx.getNodeOutputs() != null && ctx.getNodeOutputs().containsKey(nr.getNodeId());
            if (!success && !continuedFailure) {
                if (WorkflowConstants.STATUS_RUNNING.equals(nr.getStatus())) {
                    // 上次进程中断的节点：仅作审计，恢复时按未完成重新执行
                    nr.setStatus(WorkflowConstants.STATUS_FAILED);
                    nr.setErrorMessage("interrupted by restart");
                    nr.setEndTime(LocalDateTime.now());
                    workflowNodeRunMapper.updateById(nr);
                }
                continue;
            }

            completed.add(nr.getNodeId());
            Map<String, Object> output = parseNodeOutput(nr.getOutputJson());
            if (output == null) continue;
            outputs.put(nr.getNodeId(), output);
            if (ctx.getNodeOutputs() != null && !ctx.getNodeOutputs().containsKey(nr.getNodeId())) {
                ctx.getNodeOutputs().put(nr.getNodeId(), output);
            }
            Object text = output.get("text");
            if (text != null && !String.valueOf(text).isBlank()) {
                String t = String.valueOf(text);
                if (!t.startsWith("{") && !t.startsWith("[")) latestOutputText = t;
            }
            GraphNode node = nodeMap.get(nr.getNodeId());
            if (node != null && WorkflowConstants.NODE_LOOP.equals(node.getType())) {
                Object iteration = output.get("iteration");
                if (iteration instanceof Number n) {
                    latestLoopIteration = Math.max(latestLoopIteration, n.intValue());
                    ctx.getLoopIterations().put(nr.getNodeId(), n.intValue());
                }
            }
        }

        Object existingLastOutput = ctx.getVars().get("lastOutput");
        if ((existingLastOutput == null || String.valueOf(existingLastOutput).isBlank()) && latestOutputText != null) {
            ctx.getVars().put("lastOutput", latestOutputText);
        }
        if (latestLoopIteration > 0) ctx.setCurrentLoopIteration(latestLoopIteration);

        // 已完成节点实际选中的出边
        Set<String> selectedTargets = new HashSet<>();
        for (WorkflowNodeRun nr : latestByNode.values()) {
            if (!completed.contains(nr.getNodeId())) continue;
            GraphNode source = nodeMap.get(nr.getNodeId());
            if (source == null) continue;
            Map<String, Object> output = outputs.get(nr.getNodeId());
            for (GraphEdge edge : graph.getEdgesSafe()) {
                if (!nr.getNodeId().equals(edge.getSource())) continue;
                if (isSelectedEdge(source, output, edge)) selectedTargets.add(edge.getTarget());
            }
        }

        Set<String> startNodes = new HashSet<>();
        for (GraphNode node : graph.getNodes()) {
            if (WorkflowConstants.NODE_START.equals(node.getType())) startNodes.add(node.getId());
        }

        Map<String, Integer> adjusted = new HashMap<>();
        for (GraphNode node : graph.getNodes()) {
            int remaining = 0;
            int incoming = 0;
            for (GraphEdge edge : graph.getEdgesSafe()) {
                if (!node.getId().equals(edge.getTarget())) continue;
                GraphNode target = nodeMap.get(edge.getTarget());
                // 与 GraphValidator 一致：指向 LOOP 的边视为回边，不计入入度
                if (target != null && WorkflowConstants.NODE_LOOP.equals(target.getType())) continue;
                incoming++;
                if (!completed.contains(edge.getSource())) remaining++;
            }
            adjusted.put(node.getId(), remaining);
            if (incoming == 0) startNodes.add(node.getId());
        }

        List<String> ready = new ArrayList<>();
        for (GraphNode node : graph.getNodes()) {
            if (completed.contains(node.getId())) continue;
            if (adjusted.get(node.getId()) != 0) continue;
            if (startNodes.contains(node.getId()) || selectedTargets.contains(node.getId())) {
                ready.add(node.getId());
            }
        }

        boolean endCompleted = graph.getNodes().stream()
                .anyMatch(n -> WorkflowConstants.NODE_END.equals(n.getType()) && completed.contains(n.getId()));
        boolean allCompleted = endCompleted || completed.containsAll(nodeMap.keySet());
        return new Checkpoint(adjusted, ready, allCompleted);
    }

    private Map<String, Object> parseNodeOutput(String outputJson) {
        if (outputJson == null || outputJson.isBlank()) return null;
        try {
            return objectMapper.readValue(outputJson, new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            log.warn("解析工作流节点输出失败，忽略该节点输出: {}", e.getMessage());
            return null;
        }
    }

    /** 与 WorkflowEngine.activateSuccessors 的 handle 匹配语义保持一致。 */
    private boolean isSelectedEdge(GraphNode source, Map<String, Object> output, GraphEdge edge) {
        if (WorkflowConstants.NODE_CONDITION.equals(source.getType())) {
            boolean result = output != null && Boolean.TRUE.equals(output.get("result"));
            return matchesHandle(edge.getSourceHandle(), String.valueOf(result));
        }
        if (WorkflowConstants.NODE_LOOP.equals(source.getType())) {
            String handle = output == null || output.get("handle") == null
                    ? null : String.valueOf(output.get("handle"));
            if (handle == null) {
                boolean shouldBreak = output != null && Boolean.TRUE.equals(output.get("shouldBreak"));
                handle = shouldBreak ? "done" : "loop";
            }
            return matchesHandle(edge.getSourceHandle(), handle);
        }
        return true;
    }

    private boolean matchesHandle(String sourceHandle, String targetHandle) {
        return sourceHandle == null || sourceHandle.isBlank() || "default".equals(sourceHandle)
                ? "default".equals(targetHandle)
                : sourceHandle.equals(targetHandle);
    }

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
                    run.getCreateBy(), run.getStatus(), workflowCurrentNode(nodes), run.getStartTime(),
                    minutesBetween(lastActivity, now), lastNodeStatus(nodes)));
        }
        return views;
    }

    private TaskCandidate latestWorkflowCandidate(Long sessionId) {
        WorkflowRun run = workflowRunMapper.selectOne(new LambdaQueryWrapper<WorkflowRun>()
                .eq(WorkflowRun::getSessionId, sessionId)
                .eq(WorkflowRun::getStatus, WorkflowConstants.STATUS_RUNNING)
                .orderByDesc(WorkflowRun::getId)
                .last("LIMIT 1"));
        if (run == null) return null;
        List<WorkflowNodeRun> nodes = workflowNodeRunMapper.selectList(new LambdaQueryWrapper<WorkflowNodeRun>()
                .eq(WorkflowNodeRun::getRunId, run.getId()));
        LocalDateTime lastActivity = latestActivity(run.getUpdateTime(), run.getStartTime(), nodeActivity(nodes));
        return new TaskCandidate(TYPE_WORKFLOW, run.getId(), run.getStatus(), run.getStartTime(), lastActivity);
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

    private String describeNode(WorkflowGraph graph, String nodeId) {
        return graph.getNodes().stream()
                .filter(n -> nodeId.equals(n.getId()))
                .findFirst()
                .map(n -> "节点 " + (n.getName() == null || n.getName().isBlank() ? n.getId() : n.getName()))
                .orElse("节点 " + nodeId);
    }

    private void markWorkflowCompleted(WorkflowRun run, RunContext ctx) {
        Object lastOutput = ctx.getVars().get("lastOutput");
        run.setStatus(WorkflowConstants.STATUS_COMPLETED);
        run.setOutputText(lastOutput == null ? "" : String.valueOf(lastOutput));
        run.setFinishTime(LocalDateTime.now());
        run.setDurationMs(run.getStartTime() == null ? 0L
                : Duration.between(run.getStartTime(), LocalDateTime.now()).toMillis());
        workflowRunMapper.updateById(run);
    }

    private void markWorkflowFailed(Long runId, String error) {
        WorkflowRun latest = workflowRunMapper.selectById(runId);
        if (latest == null) return;
        latest.setStatus(WorkflowConstants.STATUS_FAILED);
        latest.setErrorMessage(error);
        latest.setFinishTime(LocalDateTime.now());
        latest.setDurationMs(latest.getStartTime() == null ? 0L
                : Duration.between(latest.getStartTime(), LocalDateTime.now()).toMillis());
        workflowRunMapper.updateById(latest);
    }

    // ==================== 多 Agent ====================

    private Map<String, Object> resumeMultiAgent(String taskId, String operator) {
        MultiAgentRun run = multiAgentService.loadRun(taskId);
        if (run == null) return resumeResult(false, "多 Agent 运行记录不存在", null);

        LockAttempt attempt = tryAcquireRecoveryLock(TYPE_MULTI_AGENT, taskId);
        if (!attempt.acquired()) return resumeResult(false, attempt.rejectReason(), null);
        RecoveryLock lock = attempt.lock();

        boolean async = false;
        try {
            if (MultiAgentConstants.STATUS_PAUSED.equals(run.getStatus())) {
                return resumeResult(false, "多 Agent 运行处于 HITL 暂停，请使用人工确认入口", null);
            }
            if (!MultiAgentConstants.STATUS_RUNNING.equals(run.getStatus())
                    && !"PLANNING".equals(run.getStatus())) {
                return resumeResult(false, "任务已结束（" + run.getStatus() + "），无需继续执行", null);
            }

            String resumedFrom = "PLANNING".equals(run.getStatus())
                    ? "运行入口（尚未启动）"
                    : "第 " + (run.getCurrentRound() != null ? run.getCurrentRound() : 1) + " 轮";
            async = dispatch(lock, () -> {
                try {
                    multiAgentRuntime.resumeFromCheckpoint(taskId, null);
                } catch (Exception e) {
                    log.error("多 Agent 续跑失败 runId={}", taskId, e);
                }
            });
            if (!async) return resumeResult(false, "恢复执行器繁忙，请稍后重试", null);

            audit(TYPE_MULTI_AGENT, taskId, operator, resumedFrom);
            return resumeResult(true, "已触发继续执行", resumedFrom);
        } finally {
            if (!async) releaseRecoveryLock(lock);
        }
    }

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
                    run.getStatus(), multiAgentCurrentNode(run, steps), startTime,
                    minutesBetween(lastActivity, now), lastMultiStepStatus(steps)));
        }
        return views;
    }

    private TaskCandidate latestMultiAgentCandidate(Long sessionId) {
        MultiAgentRun run = multiAgentRunMapper.selectOne(new LambdaQueryWrapper<MultiAgentRun>()
                .eq(MultiAgentRun::getSessionId, sessionId)
                .in(MultiAgentRun::getStatus, ACTIVE_STATUSES)
                .orderByDesc(MultiAgentRun::getId)
                .last("LIMIT 1"));
        if (run == null) return null;
        List<MultiAgentStep> steps = multiAgentStepMapper.selectList(new LambdaQueryWrapper<MultiAgentStep>()
                .eq(MultiAgentStep::getRunId, run.getRunId()));
        LocalDateTime startTime = run.getStartTime() != null ? run.getStartTime() : run.getCreateTime();
        LocalDateTime lastActivity = latestActivity(run.getUpdateTime(), startTime, multiStepActivity(steps));
        return new TaskCandidate(TYPE_MULTI_AGENT, run.getRunId(), run.getStatus(), startTime, lastActivity);
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

    // ==================== 公共：待办定位 / 时间 / 审计 / 异步 ====================

    private TaskCandidate latestPendingCandidate(Long sessionId) {
        List<TaskCandidate> candidates = new ArrayList<>(3);
        addIfNotNull(candidates, latestAgentCandidate(sessionId));
        addIfNotNull(candidates, latestWorkflowCandidate(sessionId));
        addIfNotNull(candidates, latestMultiAgentCandidate(sessionId));
        return candidates.stream()
                .max(Comparator.comparing(TaskCandidate::startTime,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElse(null);
    }

    private void addIfNotNull(List<TaskCandidate> list, TaskCandidate candidate) {
        if (candidate != null) list.add(candidate);
    }

    /**
     * 获取恢复锁：inFlight 仅用于同实例快速失败；跨实例互斥必须依赖 Redis。
     * Redis 未启用或不可用时直接拒绝恢复，不回退到进程内锁继续执行。
     */
    private LockAttempt tryAcquireRecoveryLock(String type, String id) {
        String memoryKey = type + ":" + id;
        if (!inFlight.add(memoryKey)) {
            return new LockAttempt(null, "任务正在恢复或执行中，请勿重复操作");
        }
        String lockName = "task-resume:" + memoryKey;
        if (!redisDistributedLock.enabled()) {
            inFlight.remove(memoryKey);
            if (fallbackDisabledWarned.compareAndSet(false, true)) {
                metrics.recordStateStoreFallback("task-recovery-lock", "disabled");
                log.warn("Redis 未启用，分布式锁不可用，已拒绝任务恢复 key={}", lockName);
            }
            return new LockAttempt(null, "分布式锁不可用，已拒绝恢复");
        }
        try {
            LockHandle handle = redisDistributedLock.tryAcquire(lockName);
            if (handle == null) {
                inFlight.remove(memoryKey);
                return new LockAttempt(null, "任务已在其他实例恢复中");
            }
            fallbackDisabledWarned.set(false);
            fallbackErrorWarned.set(false);
            metrics.recordStateStoreOperation("task-recovery-lock", "lock", "redis");
            return new LockAttempt(new RecoveryLock(lockName, handle, memoryKey), null);
        } catch (RedisUnavailableException e) {
            inFlight.remove(memoryKey);
            if (fallbackErrorWarned.compareAndSet(false, true)) {
                metrics.recordStateStoreFallback("task-recovery-lock", "error");
                log.warn("Redis 不可用，分布式锁不可用，已拒绝任务恢复 key={} reason={}",
                        lockName, e.getMessage());
            }
            return new LockAttempt(null, "分布式锁不可用，已拒绝恢复");
        }
    }

    private void releaseRecoveryLock(RecoveryLock lock) {
        if (lock == null) return;
        if (lock.handle() != null) {
            try {
                redisDistributedLock.unlock(lock.handle());
            } catch (RedisUnavailableException e) {
                log.warn("释放任务恢复分布式锁失败，将依赖 TTL 自动过期 key={} reason={}",
                        lock.name(), e.getMessage());
            }
        }
        if (lock.memoryKey() != null) {
            inFlight.remove(lock.memoryKey());
        }
    }

    private boolean dispatch(RecoveryLock lock, Runnable task) {
        try {
            resumeExecutor.execute(MdcContext.wrap(() -> {
                ScheduledFuture<?> renewal = startLockRenewal(lock);
                try {
                    task.run();
                } catch (Throwable t) {
                    log.error("任务续跑异常 key={}", lock.name(), t);
                } finally {
                    if (renewal != null) renewal.cancel(false);
                    releaseRecoveryLock(lock);
                }
            }));
            return true;
        } catch (RejectedExecutionException e) {
            log.warn("任务恢复执行器已拒绝 key={}", lock.name(), e);
            return false;
        }
    }

    /** 长任务续约：按 TTL/3 周期续期，锁已丢失或 Redis 异常时只告警，由 TTL 过期语义兜底。 */
    private ScheduledFuture<?> startLockRenewal(RecoveryLock lock) {
        if (lock == null || lock.handle() == null) return null;
        LockHandle handle = lock.handle();
        long ttlMillis = Math.max(1000L, handle.ttl().toMillis());
        long periodMillis = Math.max(1000L, ttlMillis / 3);
        return lockRenewer.scheduleAtFixedRate(() -> {
            try {
                if (!redisDistributedLock.renew(handle)) {
                    log.warn("任务恢复锁续约失败（锁可能已过期或被其他实例抢占） key={}", lock.name());
                }
            } catch (RedisUnavailableException e) {
                log.warn("任务恢复锁续约异常（Redis 不可用） key={} reason={}", lock.name(), e.getMessage());
            }
        }, periodMillis, periodMillis, TimeUnit.MILLISECONDS);
    }

    private void audit(String taskType, String taskId, String operator, String resumedFrom) {
        AuditLog entry = new AuditLog();
        entry.setUsername(operator);
        entry.setAction("TASK_RESUME");
        entry.setResourceType(taskType);
        entry.setResourceId(taskId);
        entry.setDetail("operator=" + operator + ", taskType=" + taskType
                + ", taskId=" + taskId + ", resumedFrom=" + resumedFrom);
        entry.setStatus(AuditConstants.STATUS_SUCCESS);
        auditLogService.saveAsync(entry);
    }

    private void assertSessionOwned(Long sessionId) {
        ChatSession session = chatSessionMapper.selectById(sessionId);
        String username = SecurityUtils.currentUsername();
        if (session == null || username == null || !username.equals(session.getUserIdentifier())) {
            throw new BusinessException(ResultCode.PERMISSION_DENIED, "会话不存在或无权访问");
        }
    }

    private Map<String, Object> resumeResult(boolean success, String message, String resumedFrom) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", success);
        resp.put("message", message);
        resp.put("resumedFrom", resumedFrom);
        return resp;
    }

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

    private Long parseLongOrNull(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
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

    private long minutesSince(LocalDateTime time) {
        return minutesBetween(time, LocalDateTime.now());
    }

    private long minutesBetween(LocalDateTime from, LocalDateTime to) {
        if (from == null || to == null) return 0L;
        return Math.max(0L, Duration.between(from, to).toMinutes());
    }

    private record TaskCandidate(String taskType, Object taskId, String status,
                                 LocalDateTime startTime, LocalDateTime lastActivity) {
    }

    private record WindowChains(List<LlmModel> executor, List<LlmModel> planner) {
    }

    /** 恢复锁：handle 非空表示已持有 Redis 锁；memoryKey 用于同实例快速失败清理。 */
    private record RecoveryLock(String name, LockHandle handle, String memoryKey) {
    }

    private record LockAttempt(RecoveryLock lock, String rejectReason) {
        boolean acquired() {
            return lock != null;
        }
    }

    private record Checkpoint(Map<String, Integer> adjustedInDegree, List<String> readyNodes,
                              boolean allCompleted) {
    }

    private record TaskView(String taskType, Object taskId, String title, String ownerUsername,
                            String status, String currentNode, LocalDateTime startTime,
                            long stayMinutes, String lastStepStatus) {
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
