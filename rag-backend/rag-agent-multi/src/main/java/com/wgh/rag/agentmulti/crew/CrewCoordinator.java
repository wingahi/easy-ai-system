package com.wgh.rag.agentmulti.crew;

import com.wgh.rag.agentmulti.constant.MultiAgentConstants;
import com.wgh.rag.workflow.constant.WorkflowConstants;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.agentmulti.config.MultiAgentContextProperties;
import com.wgh.rag.agentmulti.constant.MultiAgentEvents;
import com.wgh.rag.agentmulti.context.MultiAgentContext;
import com.wgh.rag.agentmulti.dto.AgentInstance;
import com.wgh.rag.agentmulti.dto.AgentInstanceResult;
import com.wgh.rag.agentmulti.dto.AgentResultCard;
import com.wgh.rag.agentmulti.dto.DAGGraph;
import com.wgh.rag.agentmulti.dto.GraphEdge;
import com.wgh.rag.agentmulti.dto.GraphNode;
import com.wgh.rag.agentmulti.entity.MultiAgentRun;
import com.wgh.rag.agentmulti.entity.MultiAgentStep;
import com.wgh.rag.agentmulti.service.AgentStepCheckpointService;
import com.wgh.rag.agentmulti.service.AgentStepCheckpointService.CheckpointSession;
import com.wgh.rag.execution.run.RunLifecycle;
import com.wgh.rag.agentmulti.service.AgentStepCheckpointService.StepDecision;
import com.wgh.rag.agentmulti.service.MultiAgentService;
import com.wgh.rag.agentmulti.support.AgentInstanceExecutor;
import com.wgh.rag.agentmulti.support.AgentResultCardBuilder;
import com.wgh.rag.agentmulti.support.SseEventSender;
import com.wgh.rag.workflow.engine.ConditionEvaluator;
import com.wgh.rag.workflow.engine.VariableResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * CrewCoordinator —— Crew 模式下的三种执行：
 * <ul>
 *   <li>SEQUENTIAL：按图边顺序串行；</li>
 *   <li>HIERARCHICAL：先 manager 规划，再 workers 执行，manager 汇总；</li>
 *   <li>PARALLEL：frontier + pendingInEdges + ThreadPoolExecutor 并发调度。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CrewCoordinator {

    private static final int THREAD_POOL_CORE = 4;
    private static final int THREAD_POOL_MAX = 8;

    private final MultiAgentService service;
    private final AgentStepCheckpointService checkpointService;
    private final AgentInstanceExecutor agentExecutor;
    private final SseEventSender sse;
    private final ObjectMapper objectMapper;
    private final ConditionEvaluator conditionEvaluator;
    private final VariableResolver variableResolver;
    private final MultiAgentContextProperties contextProperties;

    public void execute(MultiAgentRun run, List<AgentInstance> agents,
                        MultiAgentContext ctx, SseEmitter emitter) {
        execute(run, agents, ctx, emitter, checkpointService.open(run.getRunId()));
    }

    public void execute(MultiAgentRun run, List<AgentInstance> agents,
                        MultiAgentContext ctx, SseEmitter emitter, CheckpointSession session) {
        execute(run, agents, ctx, emitter, session, RunLifecycle.NOOP);
    }

    public void execute(MultiAgentRun run, List<AgentInstance> agents,
                        MultiAgentContext ctx, SseEmitter emitter, CheckpointSession session,
                        RunLifecycle lifecycle) {
        lifecycle.throwIfCancelled();
        lifecycle.heartbeat();
        String mode = run.getExecutionMode();
        CrewConfig crewCfg = parseCrewConfig(run.getCrewConfigJson());
        DAGGraph graph = crewCfg.graph;

        // 无显式图定义时按 agents 顺序合成线性链，避免空跑却标记 COMPLETED
        if ((graph == null || graph.getNodes() == null || graph.getNodes().isEmpty())
                && agents != null && !agents.isEmpty()) {
            graph = buildLinearGraph(agents);
        }

        switch (mode) {
            case "CREW_SEQUENTIAL" -> runSequential(run, agents, graph, ctx, emitter, session);
            case "CREW_HIERARCHICAL" -> runHierarchical(run, agents, crewCfg, ctx, emitter, session);
            case "CREW_PARALLEL" -> runParallel(run, agents, graph, ctx, emitter, session);
            default -> throw new IllegalStateException("未知 crew mode: " + mode);
        }

        // Collector / 汇总 —— 最后一个节点；写终态前再确认租约
        lifecycle.throwIfCancelled();
        lifecycle.heartbeat();
        collectAndFinish(run, graph, ctx, emitter);
    }

    /** HITL 恢复 —— Crew 模式暂不支持 HITL 节点，但保留接口 */
    public void resume(MultiAgentRun run, List<AgentInstance> agents,
                       MultiAgentContext ctx, String userInput, SseEmitter emitter) {
        // 简单处理：重新执行（实际可根据 step 状态跳过已完成的）
        execute(run, agents, ctx, emitter);
    }

    // ==================== SEQUENTIAL ====================

    private void runSequential(MultiAgentRun run, List<AgentInstance> agents,
                                DAGGraph graph, MultiAgentContext ctx, SseEmitter emitter,
                                CheckpointSession session) {
        if (graph == null || graph.getNodes() == null || graph.getNodes().isEmpty()) return;
        Map<String, AgentInstance> agentMap = indexAgents(agents);
        Map<String, GraphNode> nodeMap = graph.getNodes().stream()
                .collect(Collectors.toMap(GraphNode::getId, n -> n, (a, b) -> a));
        Map<String, List<GraphEdge>> outEdges = new HashMap<>();
        List<GraphEdge> edges0 = graph.getEdges();
        if (edges0 == null) edges0 = List.of();
        for (GraphEdge edge : edges0) {
            outEdges.computeIfAbsent(edge.getSource(), k -> new ArrayList<>()).add(edge);
        }

        Set<String> visited = new HashSet<>();
        List<String> frontier = new ArrayList<>(graph.getStartNodes());
        if (frontier.isEmpty()) {
            frontier.add(graph.getNodes().get(0).getId());
        }

        while (!frontier.isEmpty()) {
            // 取一个；支持多个 start node
            String nodeId = frontier.remove(0);
            if (!visited.add(nodeId)) continue;

            GraphNode node = nodeMap.get(nodeId);
            if (node == null) continue;
            if (WorkflowConstants.NODE_AGENT.equals(node.getType())) {
                AgentInstance agent = agentMap.get(node.getAgentId());
                if (agent == null) {
                    log.warn("节点 {} 未找到 agentId={}", nodeId, node.getAgentId());
                    continue;
                }
                String input = buildNodeInput(node, ctx, run.getUserGoal());
                String stepKey = "node:" + nodeId + ":iter:1";
                StepDecision decision = checkpointService.begin(session, stepKey, nodeId,
                        agent.getAgentId(), agent.getRole(), null, input);
                AgentInstanceResult result;
                if (decision.skip()) {
                    result = restoreResult(decision.step());
                } else {
                    try {
                        result = agentExecutor.execute(agent, input, ctx, emitter);
                        checkpointService.complete(session, decision.step(), result);
                    } catch (Exception e) {
                        checkpointService.fail(session, decision.step(), e.getMessage());
                        throw e;
                    }
                }

                ctx.put(nodeId + ".text", result.getOutput());
                ctx.put(agent.getAgentId() + ".text", result.getOutput());
                ctx.setVars("lastOutput", result.getOutput());
            }

            // 后继入队（CONDITION 根据 sourceHandle）
            List<GraphEdge> edges = outEdges.getOrDefault(nodeId, List.of());
            for (GraphEdge edge : edges) {
                // condition 节点 Crew 模式不使用，但兼容
                if (WorkflowConstants.NODE_CONDITION.equals(node.getType())) {
                    //  Crew 模式下不出现 CONDITION；若出现，则变量渲染后 true/false 简单判断
                    Object expr = ctx.resolvePath(nodeId + ".condition");
                    boolean bool = Boolean.TRUE.equals(expr);
                    if (edge.getSourceHandle() != null
                            && !edge.getSourceHandle().equals(String.valueOf(bool))) continue;
                }
                if (!visited.contains(edge.getTarget())) frontier.add(edge.getTarget());
            }
        }
    }

    // ==================== HIERARCHICAL ====================

    private void runHierarchical(MultiAgentRun run, List<AgentInstance> agents,
                                 CrewConfig crewCfg, MultiAgentContext ctx, SseEmitter emitter,
                                 CheckpointSession session) {
        Map<String, AgentInstance> agentMap = indexAgents(agents);
        String managerId = crewCfg.managerAgentId != null
                ? crewCfg.managerAgentId
                : findManagerFallback(agents);

        AgentInstance resolvedManager = managerId != null
                ? agentMap.get(managerId)
                : null;
        if (resolvedManager == null && !agents.isEmpty()) resolvedManager = agents.get(0);
        if (resolvedManager == null) return;
        final AgentInstance manager = resolvedManager;

        // Manager 先规划 + 汇总
        String managerPrompt = """
                你是项目协调者。请先分析目标：%s
                然后把任务拆解给以下 workers 执行：%s
                最后输出一份完整的、结构化的最终答复。
                """.formatted(run.getUserGoal(),
                agents.stream()
                        .filter(a -> !a.getAgentId().equals(manager.getAgentId()))
                        .map(a -> "- " + a.getName() + " (" + a.getRole() + ") — " + a.getGoal())
                        .collect(Collectors.joining("\n")));

        StepDecision planStep = checkpointService.begin(session, "stage:manager_plan", null,
                manager.getAgentId(), manager.getRole(), null, managerPrompt);
        AgentInstanceResult managerResult;
        if (planStep.skip()) {
            managerResult = restoreResult(planStep.step());
        } else {
            try {
                managerResult = agentExecutor.execute(manager, managerPrompt, ctx, emitter);
                checkpointService.complete(session, planStep.step(), managerResult);
            } catch (Exception e) {
                checkpointService.fail(session, planStep.step(), e.getMessage());
                throw e;
            }
        }
        ctx.put(manager.getAgentId() + ".text", managerResult.getOutput());
        ctx.blackboard().put("plan.manager", managerResult.getOutput());

        // Workers 执行（串行 + 并行可选 —— 这里默认并行，数量少直接顺序执行也可）
        final AgentInstance finalManager = manager;
        List<AgentInstance> workers = agents.stream()
                .filter(a -> !a.getAgentId().equals(finalManager.getAgentId()))
                .collect(Collectors.toList());

        List<AgentResultCard> workerCards = new ArrayList<>();
        for (AgentInstance worker : workers) {
            String input = """
                    协调者规划摘要：
                    %s

                    用户目标：%s
                    """.formatted(truncate(managerResult.getOutput()), run.getUserGoal());
            String stepKey = "worker:" + worker.getAgentId();
            StepDecision decision = checkpointService.begin(session, stepKey, null,
                    worker.getAgentId(), worker.getRole(), null, input);
            AgentInstanceResult r;
            if (decision.skip()) {
                r = restoreResult(decision.step());
                Object restoredCard = ctx.blackboard().get("card." + worker.getAgentId()).orElse(null);
                if (restoredCard instanceof AgentResultCard card) workerCards.add(card);
            } else {
                try {
                    r = agentExecutor.execute(worker, input, ctx, emitter);
                    checkpointService.complete(session, decision.step(), r);
                } catch (Exception e) {
                    checkpointService.fail(session, decision.step(), e.getMessage());
                    throw e;
                }
            }
            ctx.put(worker.getAgentId() + ".text", r.getOutput());
            if (r.getCard() != null) {
                workerCards.add(r.getCard());
                ctx.blackboard().put("card." + worker.getAgentId(), r.getCard());
                ctx.put(worker.getAgentId() + ".card", AgentResultCardBuilder.format(r.getCard()));
            }
        }

        // Manager 汇总
        String summaryPrompt = """
                以下是你作为协调者先前的规划：
                %s

                以下是各个 worker 的产出：
                %s

                请综合以上信息，给出一份面向用户的最终答复（结构化、清晰、完整）。
                """.formatted(truncate(managerResult.getOutput()),
                AgentResultCardBuilder.merge(cardsFromBlackboard(ctx, workerCards),
                        contextProperties.getSummaryMaxTokens()));

        StepDecision summaryStep = checkpointService.begin(session, "stage:manager_summary", null,
                manager.getAgentId(), manager.getRole(), null, summaryPrompt);
        AgentInstanceResult finalResult;
        if (summaryStep.skip()) {
            finalResult = restoreResult(summaryStep.step());
        } else {
            try {
                finalResult = agentExecutor.execute(manager, summaryPrompt, ctx, emitter);
                checkpointService.complete(session, summaryStep.step(), finalResult);
            } catch (Exception e) {
                checkpointService.fail(session, summaryStep.step(), e.getMessage());
                throw e;
            }
        }
        ctx.put(manager.getAgentId() + ".summary", finalResult.getOutput());
        run.setOutputText(finalResult.getOutput());
    }

    // ==================== PARALLEL ====================

    /** 优先从共享黑板读取 worker 卡片；黑板缺失时回退到本地列表。 */
    private List<AgentResultCard> cardsFromBlackboard(MultiAgentContext ctx, List<AgentResultCard> fallback) {
        List<AgentResultCard> cards = ctx.blackboard().snapshot("card.").values().stream()
                .filter(AgentResultCard.class::isInstance)
                .map(AgentResultCard.class::cast)
                .toList();
        return cards.isEmpty() ? fallback : cards;
    }

    private void runParallel(MultiAgentRun run, List<AgentInstance> agents,
                              DAGGraph graph, MultiAgentContext ctx, SseEmitter emitter,
                              CheckpointSession session) {
        if (graph == null || graph.getNodes() == null || graph.getNodes().isEmpty()) return;

        Map<String, AgentInstance> agentMap = indexAgents(agents);
        Map<String, GraphNode> nodeMap = graph.getNodes().stream()
                .collect(Collectors.toMap(GraphNode::getId, n -> n, (a, b) -> a));

        // 计算入度（忽略 sourceHandle，并行调度只关心计数）
        Map<String, Integer> pendingIn = new ConcurrentHashMap<>();
        for (GraphNode n : graph.getNodes()) pendingIn.put(n.getId(), 0);
        if (graph.getEdges() != null) {
            for (GraphEdge e : graph.getEdges()) {
                pendingIn.merge(e.getTarget(), 1, Integer::sum);
            }
        }
        // 起点入度置 0
        for (String id : graph.getStartNodes()) pendingIn.put(id, 0);

        // frontier
        Deque<String> frontier = new ArrayDeque<>();
        for (String id : pendingIn.keySet()) {
            if (pendingIn.get(id) == 0) frontier.offerLast(id);
        }

        Map<String, List<GraphEdge>> outEdges = new HashMap<>();
        List<GraphEdge> edges0 = graph.getEdges();
        if (edges0 == null) edges0 = List.of();
        for (GraphEdge edge : edges0) {
            outEdges.computeIfAbsent(edge.getSource(), k -> new ArrayList<>()).add(edge);
        }

        Set<String> submitted = ConcurrentHashMap.newKeySet();

        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                THREAD_POOL_CORE, THREAD_POOL_MAX, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(200),
                r -> new Thread(r, "crew-parallel-" + r.hashCode()),
                new ThreadPoolExecutor.AbortPolicy());

        // 调度循环：原实现起线程后立即 join，线程无并行价值；改为内联执行并保留 10 分钟截止，
        // 避免嵌套提交到统一线程池造成池饥饿
        long deadlineNanos = System.nanoTime() + TimeUnit.MINUTES.toNanos(10);
        Runnable dispatcher = () -> {
            try {
                while (true) {
                    if (System.nanoTime() > deadlineNanos) {
                        log.warn("Crew Parallel dispatcher 超时 runId={}", run.getRunId());
                        return;
                    }
                    String nodeId;
                    synchronized (frontier) {
                        while (frontier.isEmpty() && submitted.isEmpty()) return;
                        nodeId = frontier.pollFirst();
                    }
                    if (nodeId == null) {
                        Thread.sleep(20);
                        continue;
                    }
                    int remain = pendingIn.getOrDefault(nodeId, 0);
                    if (remain > 0) {
                        synchronized (frontier) { frontier.offerLast(nodeId); }
                        continue;
                    }
                    if (!submitted.add(nodeId)) continue;

                    executor.submit(() -> executeParallelNode(run, agents, ctx, emitter,
                            agentMap, nodeMap, outEdges, nodeId, pendingIn, frontier, submitted, session));
                }
            } catch (Throwable t) {
                log.error("Crew Parallel dispatcher 异常", t);
            }
        };
        try {
            dispatcher.run();
        } finally {
            executor.shutdownNow();
        }
    }

    private void executeParallelNode(MultiAgentRun run, List<AgentInstance> agents,
                                      MultiAgentContext ctx, SseEmitter emitter,
                                      Map<String, AgentInstance> agentMap,
                                      Map<String, GraphNode> nodeMap,
                                      Map<String, List<GraphEdge>> outEdges,
                                      String nodeId,
                                      Map<String, Integer> pendingIn,
                                      Deque<String> frontier, Set<String> submitted,
                                      CheckpointSession session) {
        try {
            GraphNode node = nodeMap.get(nodeId);
            if (node == null) return;
            if (WorkflowConstants.NODE_AGENT.equals(node.getType())) {
                AgentInstance agent = agentMap.get(node.getAgentId());
                if (agent != null) {
                    String input = buildNodeInput(node, ctx, run.getUserGoal());
                    String stepKey = "node:" + nodeId + ":iter:1";
                    StepDecision decision = checkpointService.begin(session, stepKey, nodeId,
                            agent.getAgentId(), agent.getRole(), null, input);
                    AgentInstanceResult result;
                    if (decision.skip()) {
                        result = restoreResult(decision.step());
                    } else {
                        try {
                            result = agentExecutor.execute(agent, input, ctx, emitter);
                            checkpointService.complete(session, decision.step(), result);
                        } catch (Exception e) {
                            checkpointService.fail(session, decision.step(), e.getMessage());
                            throw e;
                        }
                    }
                    ctx.put(nodeId + ".text", result.getOutput());
                    ctx.put(agent.getAgentId() + ".text", result.getOutput());
                }
            }

            // 后继递减入度
            List<GraphEdge> edges = outEdges.getOrDefault(nodeId, List.of());
            for (GraphEdge edge : edges) {
                int remain = pendingIn.merge(edge.getTarget(), -1, Integer::sum);
                if (remain <= 0) {
                    synchronized (frontier) { frontier.offerLast(edge.getTarget()); }
                }
            }
        } catch (Exception e) {
            log.error("并行节点 {} 执行异常", nodeId, e);
        } finally {
            submitted.remove(nodeId);
        }
    }

    private AgentInstanceResult restoreResult(MultiAgentStep step) {
        return AgentInstanceResult.builder()
                .output(step.getOutputText())
                .thought(step.getThought())
                .build();
    }

    // ==================== 收尾 ====================

    private void collectAndFinish(MultiAgentRun run, DAGGraph graph,
                                   MultiAgentContext ctx, SseEmitter emitter) {
        String output = run.getOutputText();
        if (output == null || output.isBlank()) {
            // 收集所有 agent 的 text 作为简单汇总
            StringBuilder sb = new StringBuilder();
            for (AgentInstance agent : parseAgentsRaw(run.getAgentsJson())) {
                Object text = ctx.get(agent.getAgentId() + ".text");
                if (text != null) {
                    sb.append("## ").append(agent.getName() != null ? agent.getName() : agent.getAgentId())
                            .append("\n").append(text).append("\n\n");
                }
            }
            output = sb.toString().trim();
        }
        run.setOutputText(output);
        service.finishRun(run, MultiAgentConstants.STATUS_COMPLETED, null);
        sse.send(emitter, MultiAgentEvents.MULTI_AGENT_COMPLETE,
                "runId", run.getRunId(),
                "output", output,
                "durationMs", run.getDurationMs());
    }

    // ==================== 辅助 ====================

    private Map<String, AgentInstance> indexAgents(List<AgentInstance> agents) {
        Map<String, AgentInstance> m = new LinkedHashMap<>();
        for (AgentInstance a : agents) m.put(a.getAgentId(), a);
        return m;
    }

    /** 按 agents 列表顺序合成线性链图：agent-0 → agent-1 → ... */
    private DAGGraph buildLinearGraph(List<AgentInstance> agents) {
        List<GraphNode> nodes = new ArrayList<>();
        List<GraphEdge> edges = new ArrayList<>();
        String prev = null;
        int i = 0;
        for (AgentInstance a : agents) {
            String nodeId = "agent-" + i++;
            nodes.add(GraphNode.builder().id(nodeId).type(WorkflowConstants.NODE_AGENT).agentId(a.getAgentId()).build());
            if (prev != null) {
                edges.add(GraphEdge.builder().source(prev).target(nodeId).build());
            }
            prev = nodeId;
        }
        return new DAGGraph(nodes, edges);
    }

    private String findManagerFallback(List<AgentInstance> agents) {
        // 优先 role 含 "manager" / "leader"，否则第一个
        for (AgentInstance a : agents) {
            String role = a.getRole() == null ? "" : a.getRole().toLowerCase();
            if (role.contains("manager") || role.contains("leader") || role.contains("coordinator")) {
                return a.getAgentId();
            }
        }
        return agents.isEmpty() ? null : agents.get(0).getAgentId();
    }

    private String buildNodeInput(GraphNode node, MultiAgentContext ctx, String fallback) {
        if (node != null && node.getInputTemplate() != null && !node.getInputTemplate().isBlank()) {
            return ctx.render(node.getInputTemplate());
        }
        return fallback != null ? fallback : "";
    }

    private void persistStep(MultiAgentRun run, AgentInstance agent, GraphNode node,
                              AgentInstanceResult result, int stepIdx) {
        MultiAgentStep step = new MultiAgentStep();
        step.setRunId(run.getRunId());
        step.setAgentId(agent != null ? agent.getAgentId() : (node != null ? node.getAgentId() : "unknown"));
        step.setStepIndex(stepIdx);
        step.setStatus("SUCCESS");
        step.setRole(agent != null ? agent.getRole() : (node != null ? node.getType() : null));
        step.setOutputText(result.getOutput());
        step.setToolCallsJson(toJson(result.getToolInvocations()));
        step.setRagSourcesJson(toJson(result.getRagSources()));
        step.setReflectionScore(result.getReflectionScore());
        step.setReflectionFeedback(result.getReflectionFeedback());
        step.setThought(result.getThought());
        step.setResultSummary(truncate(result.getOutput()));
        step.setStartTime(LocalDateTime.now().minusNanos(
                (result.getDurationMs() != null ? result.getDurationMs() : 0) * 1_000_000));
        step.setEndTime(LocalDateTime.now());
        step.setDurationMs(result.getDurationMs());
        step.setTokenCount(result.getTokenCount());
        service.insertStep(step);
    }

    private String truncate(String s) {
        if (s == null) return "";
        return s.length() > 500 ? s.substring(0, 500) + "..." : s;
    }

    private String toJson(Object v) {
        try { return objectMapper.writeValueAsString(v); }
        catch (Exception e) { return "[]"; }
    }

    @SuppressWarnings("unchecked")
    private List<AgentInstance> parseAgentsRaw(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) { return List.of(); }
    }

    private CrewConfig parseCrewConfig(String json) {
        CrewConfig cfg = new CrewConfig();
        if (json == null || json.isBlank()) return cfg;
        try {
            Map<String, Object> map = objectMapper.readValue(json, new TypeReference<>() {});
            cfg.mode = (String) map.get("mode");
            cfg.managerAgentId = (String) map.get("managerAgentId");
            Object g = map.get("graph");
            if (g != null) cfg.graph = objectMapper.convertValue(g, DAGGraph.class);
            if (cfg.graph == null) {
                // 尝试整个 JSON 当作 DAGGraph
                try { cfg.graph = objectMapper.readValue(json, DAGGraph.class); } catch (Exception ignore) {}
            }
        } catch (Exception ignore) {}
        if (cfg.graph == null) cfg.graph = new DAGGraph(new ArrayList<>(), new ArrayList<>());
        return cfg;
    }

    private static class CrewConfig {
        String mode;
        String managerAgentId;
        DAGGraph graph;
    }
}
