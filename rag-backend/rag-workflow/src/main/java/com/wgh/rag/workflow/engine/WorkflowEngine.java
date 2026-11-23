package com.wgh.rag.workflow.engine;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.common.log.MdcContext;
import com.wgh.rag.common.metrics.RagMetrics;
import com.wgh.rag.execution.error.ExecutionCancelledException;
import com.wgh.rag.execution.error.LeaseLostException;
import com.wgh.rag.execution.run.RunLifecycle;
import com.wgh.rag.workflow.constant.WorkflowConstants;
import com.wgh.rag.workflow.constant.WorkflowEvents;
import com.wgh.rag.workflow.config.WorkflowContextProperties;
import com.wgh.rag.workflow.dto.*;
import com.wgh.rag.workflow.engine.executor.NodeExecutionContext;
import com.wgh.rag.workflow.engine.executor.NodeConfigSupport;
import com.wgh.rag.workflow.engine.executor.NodeExecutorRegistry;
import com.wgh.rag.workflow.entity.Workflow;
import com.wgh.rag.workflow.entity.WorkflowNodeRun;
import com.wgh.rag.workflow.entity.WorkflowRun;
import com.wgh.rag.workflow.mapper.WorkflowNodeRunMapper;
import com.wgh.rag.workflow.mapper.WorkflowRunMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/**
 * 工作流执行引擎 —— 支持并行分支 + fan-in 入边等待 + 子流程嵌套。
 *
 * 调度模型：
 * - frontiers 使用 ConcurrentLinkedQueue，pendingInEdges 用 ConcurrentHashMap 维护，初始由 GraphValidator 计算。
 * - 节点执行完，对后继 pendingInEdges 自减，减到 0 的后继自动入队；CONDITION 仅激活匹配的一边。
 * - LOOP 节点保留循环体内串行语义，与回边入边等待逻辑解耦。
 * - 子流程（WORKFLOW 节点）通过递归调用 run / resume，最大嵌套 3 层，防无限递归。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowEngine {

    private static final int MAX_TOTAL_NODE_EXECUTIONS = 1000;
    private static final int MAX_NESTING_DEPTH = 3;
    private static final int WORKER_COUNT = 4;
    /** 节点输出摘要中随正文保留的单个轻量字段上限（字节） */
    private static final int SUMMARY_FIELD_MAX_BYTES = 2048;

    private final WorkflowRunMapper runMapper;
    private final WorkflowNodeRunMapper nodeRunMapper;
    private final VariableResolver variableResolver;
    private final ConditionEvaluator conditionEvaluator;
    private final NodeExecutorRegistry nodeExecutorRegistry;
    private final ObjectMapper objectMapper;
    private final RagMetrics metrics;
    private final WorkflowContextProperties contextProperties;

    // ==================== 入口 ====================

    /** 首次执行 */
    public void run(WorkflowRun run, WorkflowGraph graph, String input,
                    GraphValidator.GraphValidationResult validation,
                    BiConsumer<String, Map<String, Object>> sink) {
        run(run, graph, input, validation, false, sink);
    }

    /**
     * 首次执行（可指定安全模式）。
     * safeMode=true 时，工具节点会拒绝有写操作副作用的 HTTP/MCP 调用（分享态使用）。
     */
    public void run(WorkflowRun run, WorkflowGraph graph, String input,
                    GraphValidator.GraphValidationResult validation, boolean safeMode,
                    BiConsumer<String, Map<String, Object>> sink) {
        RunContext ctx = new RunContext();
        ctx.setSafeMode(safeMode);
        ctx.getVars().put("input", input == null ? "" : input);
        ctx.getVars().put("lastOutput", "");

        GraphNode start = startNode(graph);
        executeLoop(run, graph, validation, ctx, List.of(start.getId()), sink, 1);
    }

    /** 从 HITL 断点恢复 */
    public void resume(WorkflowRun run, WorkflowGraph graph,
                       GraphValidator.GraphValidationResult validation,
                       RunContext ctx,
                       BiConsumer<String, Map<String, Object>> sink) {
        if (ctx.getPendingNodeIds() == null || ctx.getPendingNodeIds().isEmpty()) {
            finishRun(run, WorkflowConstants.STATUS_FAILED, "HITL 恢复失败：没有可执行的后续节点", sink);
            sink.accept(WorkflowEvents.WORKFLOW_FAILED, Map.of(
                    "runId", run.getId(), "error", "HITL 恢复失败：没有可执行的后续节点"));
            return;
        }
        executeLoop(run, graph, validation, ctx, new ArrayList<>(ctx.getPendingNodeIds()), sink, 1);
    }

    /**
     * 从实例中断点恢复（非 HITL）。
     *
     * <p>读取持久化在 {@code context_json} 中的 frontier/pendingIn/submitted 游标，
     * 重放中断时尚未完成的节点；已完成节点不重复执行。中断时仍为 RUNNING 的
     * node_run 标记为 FAILED（实例中断），保留可定位的失败记录。
     */
    public void resumeInterrupted(WorkflowRun run, WorkflowGraph graph,
                                  GraphValidator.GraphValidationResult validation,
                                  RunContext ctx,
                                  BiConsumer<String, Map<String, Object>> sink,
                                  RunLifecycle lifecycle) {
        List<String> initial = new ArrayList<>();
        if (ctx.getResumeFrontier() != null) {
            initial.addAll(ctx.getResumeFrontier());
        }
        if (ctx.getResumeSubmitted() != null) {
            for (String nodeId : ctx.getResumeSubmitted()) {
                if (!initial.contains(nodeId)) {
                    initial.add(nodeId);
                }
            }
        }
        if (initial.isEmpty()) {
            finishRun(run, WorkflowConstants.STATUS_FAILED, "续跑失败：没有可恢复的执行游标", sink);
            sink.accept(WorkflowEvents.WORKFLOW_FAILED, Map.of(
                    "runId", run.getId(), "error", "续跑失败：没有可恢复的执行游标"));
            return;
        }

        List<WorkflowNodeRun> runningNodes = nodeRunMapper.selectList(new LambdaQueryWrapper<WorkflowNodeRun>()
                .eq(WorkflowNodeRun::getRunId, run.getId())
                .eq(WorkflowNodeRun::getStatus, WorkflowConstants.STATUS_RUNNING));
        for (WorkflowNodeRun nodeRun : runningNodes) {
            nodeRun.setStatus(WorkflowConstants.STATUS_FAILED);
            nodeRun.setEndTime(LocalDateTime.now());
            nodeRun.setErrorMessage("实例中断，续跑时重放该节点");
            nodeRunMapper.updateById(nodeRun);
        }

        run.setStatus(WorkflowConstants.STATUS_RUNNING);
        run.setErrorMessage(null);
        run.setFinishTime(null);
        run.setUpdateTime(LocalDateTime.now());
        runMapper.updateById(run);

        Map<String, Integer> pendingIn = ctx.getResumePendingIn() == null
                ? null : new HashMap<>(ctx.getResumePendingIn());
        executeLoop(run, graph, validation, ctx, initial, sink, 1, pendingIn,
                lifecycle == null ? RunLifecycle.NOOP : lifecycle);
    }

    // ==================== 单步调试 ====================

    /**
     * 单步推进（同步执行，最多 maxSteps 个节点）。
     *
     * <p>与 {@link #run} 完全独立的执行路径：按拓扑串行执行，命中 {@code stopAtNodeId} 或步数用尽即暂停，
     * 游标（frontier / pendingIn）持久化在 {@link RunContext}，下次调用从游标继续。
     * 普通运行链路不读取这些字段，因此关闭调试能力时引擎行为与原先一致。
     *
     * <p>每个节点都会记录输入快照（渲染后的节点配置 + 直接上游输出）与输出，便于前端单步查看。
     */
    public DebugStepResult debugStep(WorkflowRun run, WorkflowGraph graph,
                                     GraphValidator.GraphValidationResult validation,
                                     RunContext ctx, int maxSteps, String stopAtNodeId) {
        Map<String, GraphNode> nodeMap = graph.getNodes().stream()
                .collect(Collectors.toMap(GraphNode::getId, n -> n, (a, b) -> a));
        Map<String, List<GraphEdge>> outEdges = new HashMap<>();
        Map<String, List<GraphEdge>> inEdges = new HashMap<>();
        for (GraphEdge edge : graph.getEdgesSafe()) {
            outEdges.computeIfAbsent(edge.getSource(), k -> new ArrayList<>()).add(edge);
            inEdges.computeIfAbsent(edge.getTarget(), k -> new ArrayList<>()).add(edge);
        }

        Deque<String> frontier;
        Map<String, Integer> pendingIn;
        if (ctx.getDebugFrontier() == null || ctx.getDebugFrontier().isEmpty()) {
            // 首次单步：从 START 出发
            GraphNode start = startNode(graph);
            frontier = new ArrayDeque<>(List.of(start.getId()));
            pendingIn = new ConcurrentHashMap<>(validation.parallelInDegree);
            pendingIn.put(start.getId(), 0);
            ctx.getVars().putIfAbsent("input", run.getInputText() == null ? "" : run.getInputText());
            ctx.getVars().putIfAbsent("lastOutput", "");
        } else {
            frontier = new ArrayDeque<>(ctx.getDebugFrontier());
            pendingIn = new ConcurrentHashMap<>(ctx.getDebugPendingIn() == null ? Map.of() : ctx.getDebugPendingIn());
        }

        List<DebugStepResult.Step> steps = new ArrayList<>();
        Set<String> submitted = new HashSet<>();
        BiConsumer<String, Map<String, Object>> sink = (event, payload) -> { /* 调试为同步调用，无 SSE */ };
        int budget = Math.max(1, maxSteps);
        int executedTotal = ctx.getDebugExecutedCount() == null ? 0 : ctx.getDebugExecutedCount();
        int executedThisCall = 0;
        String status = WorkflowConstants.STATUS_DEBUG_PAUSED;
        String finalOutput = null;
        String error = null;

        while (!frontier.isEmpty() && executedThisCall < budget) {
            if (executedTotal >= MAX_TOTAL_NODE_EXECUTIONS) {
                error = "超过最大节点执行次数 " + MAX_TOTAL_NODE_EXECUTIONS;
                status = WorkflowConstants.STATUS_FAILED;
                break;
            }
            String nodeId = pollRunnable(frontier, pendingIn);
            if (nodeId == null) break; // 无可执行节点（理论上前置校验已排除）
            GraphNode node = nodeMap.get(nodeId);
            if (node == null) {
                error = "节点不存在：" + nodeId;
                status = WorkflowConstants.STATUS_FAILED;
                break;
            }
            int iteration = ctx.getCurrentLoopIteration() == null ? 0 : ctx.getCurrentLoopIteration();

            // ---------- LOOP 回边：仅推进轮次 ----------
            if (WorkflowConstants.NODE_LOOP.equals(node.getType()) && ctx.getLoopIterations().containsKey(nodeId)) {
                int prev = ctx.getLoopIterations().get(nodeId);
                int max = NodeConfigSupport.intConfig(node, "maxIterations", 3);
                boolean shouldBreak = prev >= max || evalBreakCondition(node, ctx);
                int nextIteration = prev;
                if (!shouldBreak) {
                    nextIteration = prev + 1;
                    ctx.getLoopIterations().put(nodeId, nextIteration);
                    ctx.setCurrentLoopIteration(nextIteration);
                }
                Map<String, Object> output = new LinkedHashMap<>();
                output.put("iteration", nextIteration);
                output.put("shouldBreak", shouldBreak);
                output.put("handle", shouldBreak ? "done" : "loop");
                ctx.getNodeOutputs().put(nodeId, output);
                appendNodeRun(run, node, nextIteration, output, 0L, WorkflowConstants.STATUS_SUCCESS, null);

                DebugStepResult.Step step = newStep(node, WorkflowConstants.STATUS_SUCCESS, nextIteration, 0L);
                step.setInput(loopInput(prev, nextIteration, shouldBreak));
                step.setOutput(output);
                steps.add(step);

                executedTotal++;
                executedThisCall++;
                activateSuccessors(node, outEdges, shouldBreak ? "done" : "loop", pendingIn, frontier, submitted);
                if (stopAtNodeId != null && stopAtNodeId.equals(nodeId)) break;
                continue;
            }

            // ---------- HITL：暂停并持久化待确认 ----------
            if (WorkflowConstants.NODE_HITL.equals(node.getType())) {
                String prompt = variableResolver.render(NodeConfigSupport.str(node.getConfig().get("prompt")), ctx);
                WorkflowNodeRun nr = new WorkflowNodeRun();
                nr.setRunId(run.getId());
                nr.setNodeId(node.getId());
                nr.setNodeType(node.getType());
                nr.setNodeName(nameOf(node));
                nr.setStatus(WorkflowConstants.STATUS_HITL_PENDING);
                nr.setIteration(iteration);
                nr.setInputJson(toJson(Map.of("prompt", prompt)));
                nr.setStartTime(LocalDateTime.now());
                nodeRunMapper.insert(nr);

                ctx.setPendingNodeIds(allTargets(outEdges.get(nodeId)));
                DebugStepResult.Step step = newStep(node, WorkflowConstants.STATUS_HITL_PENDING, iteration, 0L);
                step.setInput(Map.of("prompt", prompt));
                step.setOutput(Map.of("prompt", prompt));
                steps.add(step);
                status = WorkflowConstants.STATUS_PAUSED;
                break;
            }

            // ---------- 普通节点 ----------
            Map<String, Object> inputSnapshot = snapshotNodeInput(node, inEdges, ctx);
            WorkflowNodeRun nr = insertNodeRun(run, node, iteration, inputSnapshot);
            long t0 = System.currentTimeMillis();
            NodeResult result;
            try {
                result = executeNode(node, ctx, run, 1, sink);
            } catch (Exception e) {
                long duration = System.currentTimeMillis() - t0;
                nr.setStatus(WorkflowConstants.STATUS_FAILED);
                nr.setEndTime(LocalDateTime.now());
                nr.setDurationMs(duration);
                nr.setErrorMessage(e.getMessage());
                nodeRunMapper.updateById(nr);

                DebugStepResult.Step step = newStep(node, WorkflowConstants.STATUS_FAILED, iteration, duration);
                step.setInput(inputSnapshot);
                step.setError(e.getMessage());
                steps.add(step);

                if ("CONTINUE".equalsIgnoreCase(node.getOnError())) {
                    log.warn("调试节点 {} 失败但配置为 CONTINUE，继续执行", node.getId(), e);
                    Map<String, Object> failed = new LinkedHashMap<>();
                    failed.put("error", e.getMessage());
                    ctx.getNodeOutputs().put(node.getId(), failed);
                    activateAllSuccessors(node, outEdges, pendingIn, frontier, submitted);
                    executedTotal++;
                    executedThisCall++;
                    continue;
                }
                error = "节点「" + nameOf(node) + "」执行失败：" + e.getMessage();
                status = WorkflowConstants.STATUS_FAILED;
                break;
            }

            long duration = System.currentTimeMillis() - t0;
            // LOOP 首次进入：记录 handle/shouldBreak，供崩溃恢复时判定实际走向
            if (WorkflowConstants.NODE_LOOP.equals(node.getType())) {
                boolean breakNow = NodeConfigSupport.intConfig(node, "maxIterations", 3) <= 0;
                result.getOutput().put("shouldBreak", breakNow);
                result.getOutput().put("handle", breakNow ? "done" : "loop");
            }
            ctx.getNodeOutputs().put(node.getId(), result.getOutput());
            Object text = result.getOutput().get("text");
            updateLastOutput(ctx, result.getOutput());

            nr.setStatus(WorkflowConstants.STATUS_SUCCESS);
            nr.setOutputJson(serializeNodeOutput(result.getOutput()));
            nr.setEndTime(LocalDateTime.now());
            nr.setDurationMs(duration);
            nodeRunMapper.updateById(nr);

            DebugStepResult.Step step = newStep(node, WorkflowConstants.STATUS_SUCCESS, iteration, duration);
            step.setInput(inputSnapshot);
            step.setOutput(result.getOutput());
            steps.add(step);

            executedTotal++;
            executedThisCall++;

            if (WorkflowConstants.NODE_END.equals(node.getType())) {
                finalOutput = text != null ? String.valueOf(text) : "";
                status = WorkflowConstants.STATUS_COMPLETED;
                break;
            }

            if (WorkflowConstants.NODE_CONDITION.equals(node.getType())) {
                boolean bool = Boolean.TRUE.equals(result.getOutput().get("result"));
                activateSuccessors(node, outEdges, String.valueOf(bool), pendingIn, frontier, submitted);
            } else if (WorkflowConstants.NODE_LOOP.equals(node.getType())) {
                int max = NodeConfigSupport.intConfig(node, "maxIterations", 3);
                activateSuccessors(node, outEdges, max <= 0 ? "done" : "loop", pendingIn, frontier, submitted);
            } else {
                activateAllSuccessors(node, outEdges, pendingIn, frontier, submitted);
            }

            if (stopAtNodeId != null && stopAtNodeId.equals(nodeId)) break;
        }

        if (WorkflowConstants.STATUS_DEBUG_PAUSED.equals(status) && frontier.isEmpty()) {
            // 已无后续节点：按流程结束处理，避免游标停在一个无法推进的状态
            status = WorkflowConstants.STATUS_COMPLETED;
            if (finalOutput == null) {
                Object last = ctx.getVars().get("lastOutput");
                finalOutput = last == null ? "" : String.valueOf(last);
            }
        }

        // ---------- 持久化运行状态与调试游标 ----------
        ctx.setDebugFrontier(new ArrayList<>(frontier));
        ctx.setDebugPendingIn(new HashMap<>(pendingIn));
        ctx.setDebugExecutedCount(executedTotal);
        run.setExecutedNodes(executedTotal);
        run.setContextJson(serializeContext(ctx));
        run.setStatus(status);
        run.setUpdateTime(LocalDateTime.now());
        if (WorkflowConstants.STATUS_COMPLETED.equals(status)) {
            run.setOutputText(finalOutput);
            run.setErrorMessage(null);
            run.setFinishTime(LocalDateTime.now());
        } else if (WorkflowConstants.STATUS_FAILED.equals(status)) {
            run.setErrorMessage(error);
            run.setFinishTime(LocalDateTime.now());
        } else {
            // DEBUG_PAUSED / PAUSED：保留可继续执行的游标，不算结束
            run.setFinishTime(null);
        }
        run.setDurationMs(elapsed(run));
        runMapper.updateById(run);

        DebugStepResult result = new DebugStepResult();
        result.setRunId(run.getId());
        result.setWorkflowId(run.getWorkflowId());
        result.setStatus(status);
        result.setStepCount(steps.size());
        result.setFinished(!WorkflowConstants.STATUS_DEBUG_PAUSED.equals(status));
        result.setOutput(finalOutput);
        result.setError(error);
        result.setSteps(steps);
        Map<String, Object> contextSnapshot = new LinkedHashMap<>();
        contextSnapshot.put("vars", ctx.getVars());
        contextSnapshot.put("nodeOutputs", ctx.getNodeOutputs());
        contextSnapshot.put("loopIterations", ctx.getLoopIterations());
        result.setContext(contextSnapshot);
        return result;
    }

    /** 按队列顺序取出第一个入边已就绪的节点；未就绪节点轮转到队尾。 */
    private String pollRunnable(Deque<String> frontier, Map<String, Integer> pendingIn) {
        int size = frontier.size();
        for (int i = 0; i < size; i++) {
            String id = frontier.pollFirst();
            if (id == null) return null;
            if (pendingIn.getOrDefault(id, 0) <= 0) {
                return id;
            }
            frontier.offerLast(id);
        }
        return null;
    }

    /** 调试输入快照：渲染后的节点配置 + 直接上游节点输出。 */
    private Map<String, Object> snapshotNodeInput(GraphNode node, Map<String, List<GraphEdge>> inEdges,
                                                  RunContext ctx) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("config", renderDeep(node.getConfig(), ctx));
        Map<String, Object> upstream = new LinkedHashMap<>();
        for (GraphEdge edge : inEdges.getOrDefault(node.getId(), List.of())) {
            Map<String, Object> out = ctx.getNodeOutputs().get(edge.getSource());
            if (out != null) {
                upstream.put(edge.getSource(), out);
            }
        }
        snapshot.put("upstream", upstream);
        return snapshot;
    }

    /** 递归渲染配置中的 {{变量}} 占位符，非字符串原样保留。 */
    private Object renderDeep(Object value, RunContext ctx) {
        if (value instanceof String s) {
            return variableResolver.render(s, ctx);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> rendered = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                rendered.put(String.valueOf(entry.getKey()), renderDeep(entry.getValue(), ctx));
            }
            return rendered;
        }
        if (value instanceof List<?> list) {
            List<Object> rendered = new ArrayList<>(list.size());
            for (Object item : list) {
                rendered.add(renderDeep(item, ctx));
            }
            return rendered;
        }
        return value;
    }

    private Map<String, Object> loopInput(int prevIteration, int nextIteration, boolean shouldBreak) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("previousIteration", prevIteration);
        input.put("iteration", nextIteration);
        input.put("shouldBreak", shouldBreak);
        return input;
    }

    private DebugStepResult.Step newStep(GraphNode node, String status, int iteration, long durationMs) {
        DebugStepResult.Step step = new DebugStepResult.Step();
        step.setNodeId(node.getId());
        step.setNodeName(nameOf(node));
        step.setNodeType(node.getType());
        step.setStatus(status);
        step.setIteration(iteration);
        step.setDurationMs(durationMs);
        return step;
    }

    /** 子流程嵌套执行（引擎内部调用，不上报顶级 workflow_complete 事件） */
    public WorkflowRun runSubWorkflow(Workflow sub, WorkflowGraph graph, String input,
                                      GraphValidator.GraphValidationResult validation,
                                      int nestingDepth) {
        if (nestingDepth > MAX_NESTING_DEPTH) {
            throw new IllegalStateException("子流程嵌套超过最大层数 " + MAX_NESTING_DEPTH + "，拒绝执行");
        }
        WorkflowRun subRun = new WorkflowRun();
        subRun.setWorkflowId(sub.getId());
        subRun.setWorkflowVersion(sub.getVersion());
        subRun.setInputText(input == null ? "" : input);
        subRun.setStatus(WorkflowConstants.STATUS_RUNNING);
        subRun.setGraphSnapshot(sub.getGraphJson());
        subRun.setExecutedNodes(0);
        subRun.setTotalNodes(graph.getNodes().size());
        subRun.setStartTime(LocalDateTime.now());
        runMapper.insert(subRun);

        RunContext ctx = new RunContext();
        ctx.getVars().put("input", input == null ? "" : input);
        ctx.getVars().put("lastOutput", input == null ? "" : input);
        executeLoop(subRun, graph, validation, ctx,
                List.of(startNode(graph).getId()), (e, p) -> { /* 子流程内部静默执行 */ }, nestingDepth);
        return subRun;
    }

    // ==================== 主循环 ====================

    private void executeLoop(WorkflowRun run, WorkflowGraph graph,
                             GraphValidator.GraphValidationResult validation,
                             RunContext ctx, List<String> initialFrontier,
                             BiConsumer<String, Map<String, Object>> sink, int nestingDepth) {
        executeLoop(run, graph, validation, ctx, initialFrontier, sink, nestingDepth,
                null, RunLifecycle.NOOP);
    }

    private void executeLoop(WorkflowRun run, WorkflowGraph graph,
                             GraphValidator.GraphValidationResult validation,
                             RunContext ctx, List<String> initialFrontier,
                             BiConsumer<String, Map<String, Object>> sink, int nestingDepth,
                             Map<String, Integer> resumePendingIn, RunLifecycle lifecycle) {
        Map<String, GraphNode> nodeMap = graph.getNodes().stream()
                .collect(Collectors.toMap(GraphNode::getId, n -> n, (a, b) -> a));
        Map<String, List<GraphEdge>> outEdges = new HashMap<>();
        for (GraphEdge edge : graph.getEdgesSafe()) {
            outEdges.computeIfAbsent(edge.getSource(), k -> new ArrayList<>()).add(edge);
        }

        // 初始化入边等待计数器：中断续跑优先使用持久化游标，HITL/首次执行使用图校验结果
        Map<String, Integer> pendingIn = new ConcurrentHashMap<>(
                resumePendingIn == null || resumePendingIn.isEmpty()
                        ? validation.parallelInDegree : resumePendingIn);
        // resume 时 pendingNodeIds 可能有多个，强制这些节点 pendingIn=0 让它们入队
        for (String id : initialFrontier) {
            pendingIn.put(id, 0);
        }

        // 共享状态
        Deque<String> frontier = new ArrayDeque<>(initialFrontier);
        AtomicInteger guard = new AtomicInteger(0);
        // 续跑/HITL 恢复时沿用已执行节点数，避免执行次数上限被重置
        AtomicInteger executed = new AtomicInteger(
                ctx.getNodeOutputs() == null ? 0 : ctx.getNodeOutputs().size());
        CountDownLatch latch = new CountDownLatch(1);
        // 已提交到执行队列的节点（防重复提交）
        Set<String> submitted = ConcurrentHashMap.newKeySet();
        // 租约丢失/取消等致命信号：由 worker 记录，调度循环停止并最终上抛，不落业务终态
        AtomicReference<RuntimeException> fatal = new AtomicReference<>();

        // 先落一次游标：实例在第一个节点完成前崩溃也能从 START/当前 frontier 续跑
        snapshotCursor(ctx, frontier, pendingIn, submitted);
        persistContext(run, ctx, WorkflowConstants.STATUS_RUNNING);

        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                WORKER_COUNT, WORKER_COUNT, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(500),
                r -> new Thread(r, "wf-parallel-worker-" + r.hashCode()),
                new ThreadPoolExecutor.AbortPolicy());

        try {
            // 调度循环：原实现起线程后立即 join，线程无并行价值；改为内联执行并保留 5 分钟截止，
            // 避免嵌套提交到统一线程池造成池饥饿
            long deadlineNanos = System.nanoTime() + TimeUnit.MINUTES.toNanos(5);
            Runnable dispatcher = () -> {
                try {
                    while (true) {
                        if (fatal.get() != null) {
                            return;
                        }
                        if (System.nanoTime() > deadlineNanos) {
                            log.warn("工作流调度超时 runId={}", run.getId());
                            return;
                        }
                        String nodeId;
                        synchronized (frontier) {
                            while (frontier.isEmpty() && submitted.isEmpty()) {
                                // 所有节点都执行完了
                                return;
                            }
                            nodeId = frontier.pollFirst();
                            if (nodeId != null) {
                                int remain = pendingIn.getOrDefault(nodeId, 0);
                                if (remain > 0) {
                                    // 还有入边未就绪，放回队列尾部等待（另一个线程可能已在处理入边）
                                    frontier.offerLast(nodeId);
                                    nodeId = null;
                                } else {
                                    // 与 frontier 快照保持原子：节点要么在 frontier，要么在 submitted
                                    submitted.add(nodeId);
                                }
                            }
                        }
                        if (nodeId == null) {
                            Thread.sleep(20);
                            continue;
                        }

                        if (guard.incrementAndGet() > MAX_TOTAL_NODE_EXECUTIONS) {
                            finishRun(run, WorkflowConstants.STATUS_FAILED, "超过最大节点执行次数 " + MAX_TOTAL_NODE_EXECUTIONS, sink);
                            sink.accept(WorkflowEvents.WORKFLOW_FAILED, Map.of(
                                    "runId", run.getId(), "error", "超过最大节点执行次数"));
                            return;
                        }

                        // worker 执行节点
                        final String dispatchNodeId = nodeId;
                        executor.submit(MdcContext.wrap(() -> executeOneNode(run, graph, nodeMap, outEdges, dispatchNodeId,
                                ctx, sink, pendingIn, frontier, submitted, guard, executed,
                                latch, nestingDepth, validation, lifecycle, fatal)));
                    }
                } catch (Throwable t) {
                    log.error("工作流调度线程异常", t);
                }
            };
            dispatcher.run();
        } finally {
            executor.shutdownNow();
            latch.countDown();
        }
        RuntimeException stop = fatal.get();
        if (stop != null) {
            throw stop;
        }
    }

    private void executeOneNode(WorkflowRun run, WorkflowGraph graph,
                                Map<String, GraphNode> nodeMap, Map<String, List<GraphEdge>> outEdges,
                                String nodeId, RunContext ctx,
                                BiConsumer<String, Map<String, Object>> sink,
                                Map<String, Integer> pendingIn, Deque<String> frontier,
                                Set<String> submitted, AtomicInteger guard, AtomicInteger executed,
                                CountDownLatch latch, int nestingDepth,
                                GraphValidator.GraphValidationResult validation,
                                RunLifecycle lifecycle, AtomicReference<RuntimeException> fatal) {
        try {
            // 作业续跑：节点边界续租并响应取消；租约丢失时直接退出，不写业务终态
            lifecycle.throwIfCancelled();
            lifecycle.heartbeat();
            // 周期检查取消状态
            WorkflowRun latest = runMapper.selectById(run.getId());
            if (latest == null || WorkflowConstants.STATUS_CANCELLED.equals(latest.getStatus())) {
                sink.accept(WorkflowEvents.WORKFLOW_CANCELLED, Map.of("runId", run.getId()));
                return;
            }

            GraphNode node = nodeMap.get(nodeId);
            if (node == null) {
                failRun(run, nodeId, "节点不存在：" + nodeId, sink);
                return;
            }

            // ---------- 循环回边：仅自增轮次，不重复执行节点体 ----------
            if (WorkflowConstants.NODE_LOOP.equals(node.getType()) && ctx.getLoopIterations().containsKey(nodeId)) {
                int prev = ctx.getLoopIterations().get(nodeId);
                int max = NodeConfigSupport.intConfig(node, "maxIterations", 3);
                boolean shouldBreak = prev >= max || evalBreakCondition(node, ctx);
                int iteration = prev;
                if (!shouldBreak) {
                    iteration = prev + 1;
                    ctx.getLoopIterations().put(nodeId, iteration);
                    ctx.setCurrentLoopIteration(iteration);
                }
                Map<String, Object> output = new LinkedHashMap<>();
                output.put("iteration", iteration);
                output.put("shouldBreak", shouldBreak);
                output.put("handle", shouldBreak ? "done" : "loop");
                ctx.getNodeOutputs().put(nodeId, output);

                appendNodeRun(run, node, iteration, output, 0L, "SUCCESS", null);
                executed.incrementAndGet();
                sink.accept(WorkflowEvents.NODE_OUTPUT, event(run, node, output, 0L, iteration));

                activateSuccessors(node, outEdges, shouldBreak ? "done" : "loop",
                        pendingIn, frontier, submitted);
                submitted.remove(nodeId);
                snapshotCursor(ctx, frontier, pendingIn, submitted);
                persistContext(run, ctx, WorkflowConstants.STATUS_RUNNING);
                return;
            }

            // ---------- HITL：暂停并持久化断点 ----------
            if (WorkflowConstants.NODE_HITL.equals(node.getType())) {
                returnFromHitl(run, node, ctx, outEdges.get(nodeId), sink);
                throw new PauseSignal("HITL 暂停：" + nodeId);
            }

            // ---------- 普通节点 ----------
            sink.accept(WorkflowEvents.NODE_START, Map.of(
                    "runId", run.getId(),
                    "nodeId", node.getId(),
                    "nodeType", node.getType(),
                    "nodeName", nameOf(node),
                    "iteration", ctx.getCurrentLoopIteration()));

            WorkflowNodeRun nr = insertNodeRun(run, node, ctx.getCurrentLoopIteration());
            long t0 = System.currentTimeMillis();
            NodeResult result;
            try {
                result = executeNode(node, ctx, run, nestingDepth, sink);
            } catch (PauseSignal ps) {
                // HITL 已在 returnFromHitl 内部处理并持久化，直接向上抛出给调度器
                throw ps;
            } catch (Exception e) {
                metrics.recordWorkflowNodeFailure(node.getType());
                long duration = System.currentTimeMillis() - t0;
                nr.setStatus(WorkflowConstants.STATUS_FAILED);
                nr.setEndTime(LocalDateTime.now());
                nr.setDurationMs(duration);
                nr.setErrorMessage(e.getMessage());
                nodeRunMapper.updateById(nr);

                Map<String, Object> errPayload = new LinkedHashMap<>();
                errPayload.put("runId", run.getId());
                errPayload.put("nodeId", node.getId());
                errPayload.put("nodeName", nameOf(node));
                errPayload.put("error", e.getMessage());
                sink.accept(WorkflowEvents.NODE_ERROR, errPayload);

                if ("CONTINUE".equalsIgnoreCase(node.getOnError())) {
                    log.warn("节点 {} 失败但配置为 CONTINUE，继续执行", node.getId(), e);
                    Map<String, Object> failed = new LinkedHashMap<>();
                    failed.put("error", e.getMessage());
                    ctx.getNodeOutputs().put(node.getId(), failed);
                    activateAllSuccessors(node, outEdges, pendingIn, frontier, submitted);
                    submitted.remove(nodeId);
                    snapshotCursor(ctx, frontier, pendingIn, submitted);
                    persistContext(run, ctx, WorkflowConstants.STATUS_RUNNING);
                    return;
                }
                failRun(run, node.getId(), "节点「" + nameOf(node) + "」执行失败：" + e.getMessage(), sink);
                return;
            }

            long duration = System.currentTimeMillis() - t0;
            // 节点体已返回：写入 SUCCESS 前再确认租约与取消，避免接管实例的双写
            lifecycle.throwIfCancelled();
            lifecycle.heartbeat();
            // LOOP 首次进入：记录 handle/shouldBreak，供崩溃恢复时判定实际走向
            if (WorkflowConstants.NODE_LOOP.equals(node.getType())) {
                boolean breakNow = NodeConfigSupport.intConfig(node, "maxIterations", 3) <= 0;
                result.getOutput().put("shouldBreak", breakNow);
                result.getOutput().put("handle", breakNow ? "done" : "loop");
            }
            ctx.getNodeOutputs().put(node.getId(), result.getOutput());
            Object text = result.getOutput().get("text");
            updateLastOutput(ctx, result.getOutput());

            nr.setStatus(WorkflowConstants.STATUS_SUCCESS);
            nr.setOutputJson(serializeNodeOutput(result.getOutput()));
            nr.setEndTime(LocalDateTime.now());
            nr.setDurationMs(duration);
            nodeRunMapper.updateById(nr);
            executed.incrementAndGet();
            run.setExecutedNodes(executed.get());

            sink.accept(WorkflowEvents.NODE_OUTPUT, event(run, node, result.getOutput(), duration,
                    ctx.getCurrentLoopIteration()));

            if (WorkflowConstants.NODE_END.equals(node.getType())) {
                String outText = text != null ? String.valueOf(text) : "";
                finishRun(run, WorkflowConstants.STATUS_COMPLETED, null, sink, outText, executed.get());
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("runId", run.getId());
                payload.put("output", outText);
                payload.put("durationMs", elapsed(run));
                payload.put("executedNodes", executed.get());
                sink.accept(WorkflowEvents.WORKFLOW_COMPLETE, payload);
                return;
            }

            if (WorkflowConstants.NODE_CONDITION.equals(node.getType())) {
                boolean bool = Boolean.TRUE.equals(result.getOutput().get("result"));
                activateSuccessors(node, outEdges, String.valueOf(bool), pendingIn, frontier, submitted);
            } else if (WorkflowConstants.NODE_LOOP.equals(node.getType())) {
                int max = NodeConfigSupport.intConfig(node, "maxIterations", 3);
                activateSuccessors(node, outEdges, max <= 0 ? "done" : "loop", pendingIn, frontier, submitted);
            } else {
                activateAllSuccessors(node, outEdges, pendingIn, frontier, submitted);
            }
            submitted.remove(nodeId);
            snapshotCursor(ctx, frontier, pendingIn, submitted);
            persistContext(run, ctx, WorkflowConstants.STATUS_RUNNING);
        } catch (LeaseLostException | ExecutionCancelledException stop) {
            fatal.compareAndSet(null, stop);
        } catch (PauseSignal pause) {
            // HITL：向上抛出给 dispatcher，外层 catch 后完成 emitter
            throw pause;
        } catch (Throwable t) {
            log.error("节点执行异常 nodeId={}", nodeId, t);
            failRun(run, nodeId, "节点执行异常：" + t.getMessage(), sink);
        } finally {
            submitted.remove(nodeId);
        }
    }

    // ==================== 后继激活（入边等待） ====================

    private void activateAllSuccessors(GraphNode node, Map<String, List<GraphEdge>> outEdges,
                                        Map<String, Integer> pendingIn,
                                        Deque<String> frontier, Set<String> submitted) {
        activateSuccessors(node, outEdges, null, pendingIn, frontier, submitted);
    }

    /**
     * 节点执行完后继激活：
     * - 所有出边的后继 pendingIn 统一减 1（无论 CONDITION 选中与否、LOOP 走哪一边），确保入边等待计数正确收敛；
     * - 只有 handle 匹配的后继入队（CONDITION 只入 true 或 false；LOOP 首次只入 loop；其他节点入所有）。
     */
    private void activateSuccessors(GraphNode node, Map<String, List<GraphEdge>> outEdges,
                                     String targetHandle,
                                     Map<String, Integer> pendingIn,
                                     Deque<String> frontier, Set<String> submitted) {
        List<GraphEdge> edges = outEdges.getOrDefault(node.getId(), List.of());
        if (edges.isEmpty()) return;

        for (GraphEdge edge : edges) {
            String target = edge.getTarget();
            // 所有出边统一减入边等待计数
            int remain = pendingIn.merge(target, -1, Integer::sum);

            // 判断这条边是否被选中（CONDITION 二选一 / LOOP 二选一 / 其他全选）
            boolean selected;
            if (WorkflowConstants.NODE_CONDITION.equals(node.getType()) || WorkflowConstants.NODE_LOOP.equals(node.getType())) {
                String h = edge.getSourceHandle();
                selected = (h == null || h.isBlank() || "default".equals(h))
                        ? "default".equals(targetHandle)
                        : h.equals(targetHandle);
            } else {
                selected = true; // START / VARIABLE / LLM / RETRIEVAL / TOOL / HITL / WORKFLOW / END 之外的普通节点全部选中
            }

            if (selected && remain <= 0) {
                synchronized (frontier) { frontier.offerLast(target); }
            }
        }
    }

    // ==================== 节点执行 ====================

    private NodeResult executeNode(GraphNode node, RunContext ctx, WorkflowRun run, int nestingDepth,
                                   BiConsumer<String, Map<String, Object>> sink) throws Exception {
        return nodeExecutorRegistry.get(node.getType())
                .execute(new NodeExecutionContext(node, ctx, run, nestingDepth, sink));
    }

    // ==================== HITL ====================

    private void returnFromHitl(WorkflowRun run, GraphNode node, RunContext ctx,
                                List<GraphEdge> edges, BiConsumer<String, Map<String, Object>> sink) {
        String prompt = variableResolver.render(NodeConfigSupport.str(node.getConfig().get("prompt")), ctx);

        WorkflowNodeRun nr = new WorkflowNodeRun();
        nr.setRunId(run.getId());
        nr.setNodeId(node.getId());
        nr.setNodeType(node.getType());
        nr.setNodeName(nameOf(node));
        nr.setStatus(WorkflowConstants.STATUS_HITL_PENDING);
        nr.setIteration(ctx.getCurrentLoopIteration());
        nr.setInputJson(toJson(Map.of("prompt", prompt)));
        nr.setStartTime(LocalDateTime.now());
        nodeRunMapper.insert(nr);

        ctx.setPendingNodeIds(allTargets(edges));
        persistContext(run, ctx, WorkflowConstants.STATUS_PAUSED);
        metrics.recordWorkflowRun("paused");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", run.getId());
        payload.put("nodeRunId", nr.getId());
        payload.put("nodeId", node.getId());
        payload.put("nodeName", nameOf(node));
        payload.put("prompt", prompt);
        sink.accept(WorkflowEvents.NODE_HITL_PENDING, payload);
        throw new PauseSignal("HITL 暂停：" + node.getId());
    }

    private List<String> allTargets(List<GraphEdge> edges) {
        if (edges == null) return new ArrayList<>();
        return edges.stream().map(GraphEdge::getTarget).distinct().collect(Collectors.toList());
    }

    // ==================== 持久化 / 事件 ====================

    private WorkflowNodeRun insertNodeRun(WorkflowRun run, GraphNode node, int iteration) {
        return insertNodeRun(run, node, iteration, node.getConfig());
    }

    /** 调试用：写入自定义输入快照（渲染后的配置 + 上游输出）。 */
    private WorkflowNodeRun insertNodeRun(WorkflowRun run, GraphNode node, int iteration, Object inputSnapshot) {
        WorkflowNodeRun nr = new WorkflowNodeRun();
        nr.setRunId(run.getId());
        nr.setNodeId(node.getId());
        nr.setNodeType(node.getType());
        nr.setNodeName(nameOf(node));
        nr.setStatus(WorkflowConstants.STATUS_RUNNING);
        nr.setIteration(iteration);
        nr.setInputJson(toJson(inputSnapshot));
        nr.setStartTime(LocalDateTime.now());
        nodeRunMapper.insert(nr);
        return nr;
    }

    private void appendNodeRun(WorkflowRun run, GraphNode node, int iteration,
                              Map<String, Object> output, long duration,
                              String status, String error) {
        WorkflowNodeRun nr = new WorkflowNodeRun();
        nr.setRunId(run.getId());
        nr.setNodeId(node.getId());
        nr.setNodeType(node.getType());
        nr.setNodeName(nameOf(node));
        nr.setStatus(status);
        nr.setIteration(iteration);
        nr.setInputJson(toJson(node.getConfig()));
        nr.setOutputJson(toJson(output));
        nr.setErrorMessage(error);
        nr.setStartTime(LocalDateTime.now().minusNanos(Math.max(duration, 0) * 1_000_000));
        nr.setEndTime(LocalDateTime.now());
        nr.setDurationMs(duration);
        nodeRunMapper.insert(nr);
    }

    /** 快照当前调度游标，供实例中断后从最后一个完成节点继续。 */
    private void snapshotCursor(RunContext ctx, Deque<String> frontier,
                                Map<String, Integer> pendingIn, Set<String> submitted) {
        synchronized (frontier) {
            ctx.setResumeFrontier(new ArrayList<>(frontier));
        }
        ctx.setResumePendingIn(new HashMap<>(pendingIn));
        ctx.setResumeSubmitted(new ArrayList<>(submitted));
    }

    private void persistContext(WorkflowRun run, RunContext ctx, String status) {
        run.setContextJson(serializeContext(ctx));
        run.setStatus(status);
        run.setUpdateTime(LocalDateTime.now());
        runMapper.updateById(run);
    }

    // ==================== 节点输出 / 上下文落库上限 ====================

    /**
     * 序列化单个节点输出用于落库（output_json）。
     * 超过 {@code wgh.workflow.context.node-output-max-bytes} 时只落摘要：保留 text 预览与轻量字段，
     * 并写入 {@code truncated=true} / {@code originalBytes} 截断标记。内存中的完整输出不受影响。
     */
    private String serializeNodeOutput(Map<String, Object> output) {
        String json = toJson(output);
        int maxBytes = contextProperties.getNodeOutputMaxBytes();
        if (maxBytes <= 0 || byteSize(json) <= maxBytes) return json;
        return toJson(summarizeNodeOutput(output, maxBytes));
    }

    /**
     * 序列化运行上下文用于落库（context_json）。
     * 超过 {@code wgh.workflow.context.context-json-max-bytes} 时，按节点插入顺序（早期优先）
     * 先把 nodeOutputs 摘要化，仍超限则逐条剔除最早的节点输出。不修改内存中的 ctx。
     */
    public String serializeContext(RunContext ctx) {
        int maxBytes = contextProperties.getContextJsonMaxBytes();
        String json = toJson(ctx);
        if (maxBytes <= 0 || byteSize(json) <= maxBytes) return json;

        // 超限才构造可变快照，避免常规路径多一次序列化/反序列化
        Map<String, Object> snapshot = objectMapper.convertValue(ctx,
                new TypeReference<LinkedHashMap<String, Object>>() {});
        Object rawOutputs = snapshot.get("nodeOutputs");
        if (!(rawOutputs instanceof Map<?, ?> outputs) || outputs.isEmpty()) return json;

        @SuppressWarnings("unchecked")
        Map<String, Object> nodeOutputs = (Map<String, Object>) outputs;
        int nodeMaxBytes = contextProperties.getNodeOutputMaxBytes();

        // 第一轮：早期节点优先摘要（保留 text 预览与轻量字段）
        for (Map.Entry<String, Object> entry : nodeOutputs.entrySet()) {
            if (byteSize(json) <= maxBytes) break;
            entry.setValue(summarizeNodeOutput(asOutputMap(entry.getValue()), nodeMaxBytes));
            json = toJson(snapshot);
        }

        // 第二轮：仍然超限则按早期优先整体剔除
        Iterator<Map.Entry<String, Object>> it = nodeOutputs.entrySet().iterator();
        while (it.hasNext() && byteSize(json) > maxBytes) {
            it.next();
            it.remove();
            json = toJson(snapshot);
        }
        if (byteSize(json) > maxBytes) {
            log.warn("context_json 超过上限 {} 字节：nodeOutputs 已全部摘要/剔除后仍为 {} 字节", maxBytes, byteSize(json));
        }
        return json;
    }

    /** 节点输出摘要：text 截取预览 + 轻量字段，附带截断标记；摘要本身不超过 maxBytes。已摘要的输出原样返回。 */
    private Map<String, Object> summarizeNodeOutput(Map<String, Object> output, int maxBytes) {
        if (Boolean.TRUE.equals(output.get("truncated"))) return output;

        int originalBytes = byteSize(toJson(output));
        Map<String, Object> summary = new LinkedHashMap<>();
        String preview = textOf(output.get("text"));
        int limit = previewLimit(maxBytes);
        if (preview.length() > limit) preview = preview.substring(0, limit);
        if (!preview.isEmpty()) summary.put("text", preview);

        for (Map.Entry<String, Object> entry : output.entrySet()) {
            String key = entry.getKey();
            if ("text".equals(key) || "truncated".equals(key) || "originalBytes".equals(key)) continue;
            if (isLightweightValue(entry.getValue())) summary.put(key, entry.getValue());
        }

        // 摘要仍超过单节点上限时：先持续截短 text，再逐个丢弃非 text 字段
        summary.put("truncated", true);
        summary.put("originalBytes", originalBytes);
        while (maxBytes > 0 && byteSize(toJson(summary)) > maxBytes) {
            Object text = summary.get("text");
            if (text instanceof String s && !s.isEmpty()) {
                summary.put("text", s.substring(0, s.length() / 2));
            } else if (!removeLastNonTextField(summary)) {
                break;
            }
        }
        return summary;
    }

    /** 恢复判定依赖的轻量决策字段，摘要/截断时不允许丢弃。 */
    private static final java.util.Set<String> PROTECTED_OUTPUT_KEYS = java.util.Set.of(
            "text", "truncated", "originalBytes", "result", "handle", "shouldBreak", "iteration", "maxIterations");

    /** 去掉摘要中最后一个非 text 字段；没有可去字段时返回 false。 */
    private boolean removeLastNonTextField(Map<String, Object> summary) {
        String last = null;
        for (String key : summary.keySet()) {
            if (!PROTECTED_OUTPUT_KEYS.contains(key)) last = key;
        }
        return last != null && summary.remove(last) != null;
    }

    /**
     * 更新 lastOutput：只接受「最近一个有意义的文本输出」——非空白字符串，且不是结构化 JSON 对象/数组，
     * 避免表达式节点等产出的大 JSON 覆盖真正的文本结果。
     */
    private void updateLastOutput(RunContext ctx, Map<String, Object> output) {
        Object text = output.get("text");
        if (!(text instanceof CharSequence cs)) return;
        String value = cs.toString().trim();
        if (value.isEmpty()) return;
        char first = value.charAt(0);
        if (first == '{' || first == '[') {
            try {
                objectMapper.readTree(value);
                return; // 合法 JSON 结构，不作为文本输出
            } catch (Exception ignored) {
                // 解析失败说明只是以括号开头的普通文本（如 Markdown 链接），照常写入
            }
        }
        ctx.getVars().put("lastOutput", text);
    }

    private Map<String, Object> asOutputMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cast = (Map<String, Object>) map;
            return cast;
        }
        Map<String, Object> wrapped = new LinkedHashMap<>();
        if (value != null) wrapped.put("text", textOf(value));
        return wrapped;
    }

    private String textOf(Object text) {
        if (text == null) return "";
        return text instanceof CharSequence cs ? cs.toString() : String.valueOf(text);
    }

    /** 摘要中 text 预览长度：与节点上限挂钩，限制在 256~2000 字符。 */
    private int previewLimit(int maxBytes) {
        if (maxBytes <= 0) return 2000;
        return Math.max(256, Math.min(2000, maxBytes / 8));
    }

    /** 轻量字段判定：标量或序列化后不超过 2KB 的字段随摘要保留。 */
    private boolean isLightweightValue(Object value) {
        if (value == null) return false;
        if (value instanceof CharSequence || value instanceof Number || value instanceof Boolean) {
            return byteSize(String.valueOf(value)) <= SUMMARY_FIELD_MAX_BYTES;
        }
        return byteSize(toJson(value)) <= SUMMARY_FIELD_MAX_BYTES;
    }

    private int byteSize(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    private void failRun(WorkflowRun run, String nodeId, String error,
                         BiConsumer<String, Map<String, Object>> sink) {
        finishRun(run, WorkflowConstants.STATUS_FAILED, error, sink);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", run.getId());
        payload.put("nodeId", nodeId);
        payload.put("error", error);
        sink.accept(WorkflowEvents.WORKFLOW_FAILED, payload);
    }

    private void finishRun(WorkflowRun run, String status, String error,
                           BiConsumer<String, Map<String, Object>> sink) {
        finishRun(run, status, error, sink, null, run.getExecutedNodes() != null ? run.getExecutedNodes() : 0);
    }

    private void finishRun(WorkflowRun run, String status, String error,
                           BiConsumer<String, Map<String, Object>> sink,
                           String output, int executed) {
        run.setStatus(status);
        run.setErrorMessage(error);
        run.setOutputText(output);
        run.setExecutedNodes(executed);
        run.setFinishTime(LocalDateTime.now());
        run.setDurationMs(elapsed(run));
        runMapper.updateById(run);
        metrics.recordWorkflowRun(status);
    }

    private long elapsed(WorkflowRun run) {
        if (run.getStartTime() == null) return 0L;
        return java.time.Duration.between(run.getStartTime(), LocalDateTime.now()).toMillis();
    }

    private Map<String, Object> event(WorkflowRun run, GraphNode node,
                                      Map<String, Object> output, long duration, int iteration) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", run.getId());
        payload.put("nodeId", node.getId());
        payload.put("nodeType", node.getType());
        payload.put("nodeName", nameOf(node));
        payload.put("output", output);
        payload.put("durationMs", duration);
        payload.put("iteration", iteration);
        return payload;
    }

    // ==================== 工具方法 ====================

    private GraphNode startNode(WorkflowGraph graph) {
        return graph.getNodes().stream()
                .filter(n -> WorkflowConstants.NODE_START.equals(n.getType()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("工作流缺少 START 节点"));
    }

    private boolean evalBreakCondition(GraphNode node, RunContext ctx) {
        Object expr = node.getConfig() != null ? node.getConfig().get("breakCondition") : null;
        if (expr == null || String.valueOf(expr).isBlank()) return false;
        return conditionEvaluator.evaluate(variableResolver.render(String.valueOf(expr), ctx));
    }

    private String nameOf(GraphNode node) {
        return node.getName() != null && !node.getName().isBlank()
                ? node.getName() : node.getType() + "-" + node.getId();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("序列化失败", e);
            return "{}";
        }
    }

    /** 供 WorkflowService 反序列化上下文使用 */
    public RunContext parseContext(String json) {
        if (json == null || json.isBlank()) return new RunContext();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("运行上下文解析失败，使用空上下文", e);
            return new RunContext();
        }
    }

}
