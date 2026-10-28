package com.wgh.rag.agentmulti.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.agentmulti.context.MultiAgentContext;
import com.wgh.rag.agentmulti.dto.AgentInstance;
import com.wgh.rag.agentmulti.dto.AgentInstanceResult;
import com.wgh.rag.agentmulti.dto.DAGGraph;
import com.wgh.rag.agentmulti.dto.GraphEdge;
import com.wgh.rag.agentmulti.dto.GraphNode;
import com.wgh.rag.agentmulti.entity.MultiAgentRun;
import com.wgh.rag.agentmulti.entity.MultiAgentStep;
import com.wgh.rag.agentmulti.runtime.MultiAgentRuntime;
import com.wgh.rag.agentmulti.service.MultiAgentService;
import com.wgh.rag.agentmulti.support.AgentInstanceExecutor;
import com.wgh.rag.agentmulti.support.SseEventSender;
import com.wgh.rag.workflow.engine.ConditionEvaluator;
import com.wgh.rag.workflow.engine.VariableResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * GraphCoordinator —— DAG/状态图驱动的多 Agent 调度。
 * <p>
 * 节点类型：START / AGENT / CONDITION / LOOP / HITL / END
 * 复用 workflow 的 ConditionEvaluator + VariableResolver；
 * HITL 通过抛出 MultiAgentRuntime.PauseSignal 暂停。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GraphCoordinator {

    private final MultiAgentService service;
    private final AgentInstanceExecutor agentExecutor;
    private final SseEventSender sse;
    private final ObjectMapper objectMapper;
    private final ConditionEvaluator conditionEvaluator;
    private final VariableResolver variableResolver;

    public void execute(MultiAgentRun run, List<AgentInstance> agents,
                        DAGGraph graph, MultiAgentContext ctx, SseEmitter emitter) {
        if (graph == null || graph.getNodes() == null || graph.getNodes().isEmpty()) {
            sse.send(emitter, "multi_agent_error", Map.of("runId", run.getRunId(), "error", "Graph 为空"));
            return;
        }

        Map<String, AgentInstance> agentMap = indexAgents(agents);
        Map<String, GraphNode> nodeMap = graph.getNodes().stream()
                .collect(Collectors.toMap(GraphNode::getId, n -> n, (a, b) -> a));
        Map<String, List<GraphEdge>> outEdges = new HashMap<>();
        List<GraphEdge> edges0 = graph.getEdges();
        if (edges0 == null) edges0 = List.of();
        for (GraphEdge edge : edges0) {
            outEdges.computeIfAbsent(edge.getSource(), k -> new ArrayList<>()).add(edge);
        }

        List<String> frontier = new ArrayList<>(graph.getStartNodes());
        if (frontier.isEmpty()) frontier.add(graph.getNodes().get(0).getId());

        Set<String> visited = new HashSet<>();
        int stepIdx = 0;

        // 初始化所有 LOOP 节点的迭代计数
        for (GraphNode n : graph.getNodes()) {
            if ("LOOP".equals(n.getType())) ctx.loopIterations().put(n.getId(), 0);
        }

        while (!frontier.isEmpty()) {
            String nodeId = frontier.remove(0);
            GraphNode node = nodeMap.get(nodeId);
            if (node == null) continue;

            String type = node.getType() == null ? "" : node.getType();
            boolean terminate = false;

            switch (type) {
                case "START" -> {
                    ctx.put(nodeId + ".text", run.getUserGoal() == null ? "" : run.getUserGoal());
                    ctx.setVars("lastOutput", run.getUserGoal() == null ? "" : run.getUserGoal());
                }
                case "AGENT" -> {
                    AgentInstance agent = agentMap.get(node.getAgentId());
                    if (agent == null) {
                        log.warn("Graph 节点 {} agentId={} 不存在", nodeId, node.getAgentId());
                        continue;
                    }
                    String input = buildNodeInput(node, ctx, run.getUserGoal());
                    AgentInstanceResult result = agentExecutor.execute(agent, input, ctx, emitter);
                    ctx.put(nodeId + ".text", result.getOutput());
                    ctx.put(agent.getAgentId() + ".text", result.getOutput());
                    ctx.setVars("lastOutput", result.getOutput());

                    persistStep(run, agent, node, result, stepIdx++);
                }
                case "CONDITION" -> {
                    String rendered = node.getExpression();
                    // VariableResolver 需要 RunContext；这里用简化的 VariableResolver.render（不 import RunContext）
                    // 直接复用 workflow 的 VariableResolver.render 会需要 RunContext，我们改用 MultiAgentContext.render
                    String expr = ctx.render(node.getExpression());
                    boolean bool = conditionEvaluator.evaluate(expr);
                    ctx.put(nodeId + ".result", bool);
                    ctx.put(nodeId + ".condition", bool);
                    ctx.put(nodeId + ".rendered", expr);

                    // 只激活匹配 handle
                    List<GraphEdge> edges = outEdges.getOrDefault(nodeId, List.of());
                    for (GraphEdge edge : edges) {
                        String handle = edge.getSourceHandle();
                        if (handle == null || handle.isBlank()
                                || handle.equalsIgnoreCase(String.valueOf(bool))) {
                            frontier.add(edge.getTarget());
                        }
                    }
                    visited.add(nodeId);
                    // CONDITION 不往后走
                    continue;
                }
                case "LOOP" -> {
                    int iteration = ctx.incrementLoop(nodeId);
                    ctx.put(nodeId + ".iteration", iteration);
                    int max = node.getMaxIterations() != null ? node.getMaxIterations() : 3;
                    boolean breakNow = iteration >= max || evalBreakCondition(node, ctx);
                    ctx.put(nodeId + ".breaked", breakNow);

                    // 走 done 或 loop 边
                    String handle = breakNow ? "done" : "loop";
                    List<GraphEdge> edges = outEdges.getOrDefault(nodeId, List.of());
                    for (GraphEdge edge : edges) {
                        String h = edge.getSourceHandle();
                        if (h == null || h.isBlank() || h.equals(handle)) {
                            frontier.add(edge.getTarget());
                        }
                    }
                    visited.add(nodeId);
                    continue;
                }
                case "HITL" -> {
                    String prompt = ctx.render(node.getPrompt());
                    MultiAgentStep step = new MultiAgentStep();
                    step.setRunId(run.getRunId());
                    step.setAgentId("HITL");
                    step.setStepIndex(stepIdx++);
                    step.setStatus("HITL_PENDING");
                    step.setHitlExpectedInput(prompt);
                    step.setStartTime(LocalDateTime.now());
                    service.insertStep(step);

                    // 写入 pendingAgents 让 resume 能识别后继
                    for (GraphEdge edge : outEdges.getOrDefault(nodeId, List.of())) {
                        ctx.getPendingAgents(nodeId).add(edge.getTarget());
                    }

                    run.setStatus("PAUSED");
                    service.saveRun(run);
                    sse.send(emitter, "agent_hitl_pending", Map.of(
                            "runId", run.getRunId(), "nodeId", nodeId, "prompt", prompt));
                    throw new MultiAgentRuntime.PauseSignal("HITL 暂停：" + nodeId);
                }
                case "END" -> {
                    String outTemplate = node.getConfig() != null ? String.valueOf(node.getConfig().getOrDefault("output", "")) : "";
                    String output;
                    if (outTemplate.isBlank()) {
                        output = String.valueOf(ctx.getOrDefault("lastOutput", ""));
                    } else {
                        output = ctx.render(outTemplate);
                    }
                    run.setOutputText(output);
                    run.setStatus("COMPLETED");
                    service.finishRun(run, "COMPLETED", null);
                    sse.send(emitter, "multi_agent_complete", Map.of(
                            "runId", run.getRunId(), "output", output, "durationMs", run.getDurationMs()));
                    terminate = true;
                }
                default -> log.warn("未知 Graph 节点类型: {}", type);
            }

            visited.add(nodeId);
            if (terminate) break;

            // 激活后继（非 CONDITION/LOOP/HITL/END 节点）
            for (GraphEdge edge : outEdges.getOrDefault(nodeId, List.of())) {
                if (!visited.contains(edge.getTarget())) {
                    frontier.add(edge.getTarget());
                }
            }
        }
    }

    /** HITL 恢复执行 —— 从 ctx.pendingAgents 取后继继续跑 */
    public void resume(MultiAgentRun run, List<AgentInstance> agents,
                        DAGGraph graph, MultiAgentContext ctx, String userInput,
                        SseEmitter emitter) {
        if (graph == null || graph.getNodes() == null || graph.getNodes().isEmpty()) return;
        if (userInput != null && !userInput.isBlank()) {
            ctx.setVars("hitlInput", userInput);
            ctx.setVars("lastOutput", userInput);
        }

        // 找到 PAUSED step，取后继作为新的 frontier
        Set<String> allTargets = new LinkedHashSet<>();
        for (Set<String> s : ctx.pendingAgents().values()) allTargets.addAll(s);

        // 没有 pending 就从头走
        if (allTargets.isEmpty()) {
            execute(run, agents, graph, ctx, emitter);
            return;
        }

        // 构造简化的 DAGGraph（从 allTargets 开始的连通子图）
        // 简单做法：把 allTargets 加进 startNodes 集合
        List<String> frontier = new ArrayList<>(allTargets);
        executeFromFrontier(run, agents, graph, ctx, emitter, frontier);
    }

    private void executeFromFrontier(MultiAgentRun run, List<AgentInstance> agents,
                                      DAGGraph graph, MultiAgentContext ctx,
                                      SseEmitter emitter, List<String> initialFrontier) {
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

        Deque<String> frontier = new ArrayDeque<>(initialFrontier);
        Set<String> visited = new HashSet<>();
        int stepIdx = countExistingSteps(run.getRunId());

        while (!frontier.isEmpty()) {
            String nodeId = frontier.pollFirst();
            GraphNode node = nodeMap.get(nodeId);
            if (node == null) continue;

            String type = node.getType() == null ? "" : node.getType();
            boolean terminate = false;
            switch (type) {
                case "AGENT" -> {
                    AgentInstance agent = agentMap.get(node.getAgentId());
                    if (agent == null) continue;
                    String input = buildNodeInput(node, ctx, run.getUserGoal());
                    AgentInstanceResult result = agentExecutor.execute(agent, input, ctx, emitter);
                    ctx.put(nodeId + ".text", result.getOutput());
                    ctx.put(agent.getAgentId() + ".text", result.getOutput());
                    ctx.setVars("lastOutput", result.getOutput());
                    persistStep(run, agent, node, result, stepIdx++);
                }
                case "CONDITION" -> {
                    String expr = ctx.render(node.getExpression());
                    boolean bool = conditionEvaluator.evaluate(expr);
                    List<GraphEdge> edges = outEdges.getOrDefault(nodeId, List.of());
                    for (GraphEdge edge : edges) {
                        String h = edge.getSourceHandle();
                        if (h == null || h.isBlank() || h.equalsIgnoreCase(String.valueOf(bool))) {
                            frontier.offerLast(edge.getTarget());
                        }
                    }
                    visited.add(nodeId);
                    continue;
                }
                case "LOOP" -> {
                    int iteration = ctx.incrementLoop(nodeId);
                    int max = node.getMaxIterations() != null ? node.getMaxIterations() : 3;
                    boolean breakNow = iteration >= max || evalBreakCondition(node, ctx);
                    String handle = breakNow ? "done" : "loop";
                    for (GraphEdge edge : outEdges.getOrDefault(nodeId, List.of())) {
                        String h = edge.getSourceHandle();
                        if (h == null || h.isBlank() || h.equals(handle)) {
                            frontier.offerLast(edge.getTarget());
                        }
                    }
                    visited.add(nodeId);
                    continue;
                }
                case "HITL" -> {
                    String prompt = ctx.render(node.getPrompt());
                    run.setStatus("PAUSED");
                    service.saveRun(run);
                    sse.send(emitter, "agent_hitl_pending", Map.of("runId", run.getRunId(), "nodeId", nodeId, "prompt", prompt));
                    throw new MultiAgentRuntime.PauseSignal("HITL 暂停：" + nodeId);
                }
                case "END" -> {
                    String outTemplate = node.getConfig() != null ? String.valueOf(node.getConfig().getOrDefault("output", "")) : "";
                    String output = outTemplate.isBlank()
                            ? String.valueOf(ctx.getOrDefault("lastOutput", ""))
                            : ctx.render(outTemplate);
                    run.setOutputText(output);
                    run.setStatus("COMPLETED");
                    service.finishRun(run, "COMPLETED", null);
                    sse.send(emitter, "multi_agent_complete", Map.of("runId", run.getRunId(), "output", output, "durationMs", run.getDurationMs()));
                    terminate = true;
                }
            }
            visited.add(nodeId);
            if (terminate) break;
            for (GraphEdge edge : outEdges.getOrDefault(nodeId, List.of())) {
                if (!visited.contains(edge.getTarget())) frontier.offerLast(edge.getTarget());
            }
        }
    }

    // ==================== 辅助 ====================

    private Map<String, AgentInstance> indexAgents(List<AgentInstance> agents) {
        Map<String, AgentInstance> m = new LinkedHashMap<>();
        for (AgentInstance a : agents) m.put(a.getAgentId(), a);
        return m;
    }

    private boolean evalBreakCondition(GraphNode node, MultiAgentContext ctx) {
        Object raw = node.getBreakCondition();
        if (raw == null) return false;
        String rendered = ctx.render(String.valueOf(raw));
        return conditionEvaluator.evaluate(rendered);
    }

    private String buildNodeInput(GraphNode node, MultiAgentContext ctx, String fallback) {
        if (node.getInputTemplate() != null && !node.getInputTemplate().isBlank()) {
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
        step.setResultSummary(result.getOutput() != null && result.getOutput().length() > 500
                ? result.getOutput().substring(0, 500) + "..." : result.getOutput());
        step.setStartTime(LocalDateTime.now().minusNanos(
                (result.getDurationMs() != null ? result.getDurationMs() : 0) * 1_000_000));
        step.setEndTime(LocalDateTime.now());
        step.setDurationMs(result.getDurationMs());
        step.setTokenCount(result.getTokenCount());
        service.insertStep(step);
    }

    private int countExistingSteps(String runId) {
        long c = service.getRunState(runId).get("steps") instanceof List<?> list ? list.size() : 0;
        return (int) c;
    }

    private String toJson(Object v) {
        try { return objectMapper.writeValueAsString(v); }
        catch (Exception e) { return "[]"; }
    }
}
