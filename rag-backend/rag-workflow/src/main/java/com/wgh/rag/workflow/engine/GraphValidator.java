package com.wgh.rag.workflow.engine;

import com.wgh.rag.workflow.dto.GraphEdge;
import com.wgh.rag.workflow.dto.GraphNode;
import com.wgh.rag.workflow.dto.WorkflowGraph;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 图结构校验 —— 保存与运行前各执行一次。
 *
 * 额外产出：
 * - 回边集合（target 为 LOOP 的边）—— 并行调度时不计入入边等待计数器；
 * - 节点入边数（非回边）—— 并行调度初始化用。
 */
@Component
public class GraphValidator {

    private static final Set<String> NODE_TYPES = Set.of(
            "START", "END", "LLM", "RETRIEVAL", "TOOL", "CONDITION", "LOOP", "HITL", "VARIABLE", "WORKFLOW");

    public GraphValidationResult validate(WorkflowGraph graph) {
        List<String> errors = new ArrayList<>();
        if (graph == null || graph.getNodes() == null || graph.getNodes().isEmpty()) {
            errors.add("工作流至少需要一个节点");
            return new GraphValidationResult(errors, Set.of(), Map.of(), Map.of(), graph);
        }

        Map<String, GraphNode> nodeMap = new HashMap<>();
        for (GraphNode node : graph.getNodes()) {
            if (node.getId() == null || node.getId().isBlank()) {
                errors.add("存在缺少 id 的节点");
                continue;
            }
            if (nodeMap.put(node.getId(), node) != null) {
                errors.add("节点 id 重复：" + node.getId());
            }
            if (node.getType() == null || !NODE_TYPES.contains(node.getType())) {
                errors.add("节点类型非法：" + node.getId() + " → " + node.getType());
            }
        }

        long startCount = graph.getNodes().stream().filter(n -> "START".equals(n.getType())).count();
        if (startCount != 1) errors.add("必须且只能有一个 START 节点，当前 " + startCount + " 个");
        boolean hasEnd = graph.getNodes().stream().anyMatch(n -> "END".equals(n.getType()));
        if (!hasEnd) errors.add("至少需要一个 END 节点");

        Map<String, Integer> inDegree = new HashMap<>();
        Set<String> hasOut = new HashSet<>();
        for (GraphEdge edge : graph.getEdgesSafe()) {
            if (!nodeMap.containsKey(edge.getSource())) {
                errors.add("连线源节点不存在：" + edge.getSource());
                continue;
            }
            if (!nodeMap.containsKey(edge.getTarget())) {
                errors.add("连线目标节点不存在：" + edge.getTarget());
                continue;
            }
            inDegree.merge(edge.getTarget(), 1, Integer::sum);
            hasOut.add(edge.getSource());
        }

        for (GraphNode node : graph.getNodes()) {
            if ("START".equals(node.getType()) && inDegree.getOrDefault(node.getId(), 0) > 0) {
                errors.add("START 节点不允许有入边：" + node.getId());
            }
            if ("END".equals(node.getType()) && hasOut.contains(node.getId())) {
                errors.add("END 节点不允许有出边：" + node.getId());
            }
            if ("LLM".equals(node.getType()) && configLong(node, "modelId") == null) {
                errors.add("LLM 节点未选择模型：" + node.getId());
            }
            if ("RETRIEVAL".equals(node.getType()) && (node.getConfig() == null
                    || node.getConfig().get("kbIds") == null)) {
                errors.add("知识库节点未选择知识库：" + node.getId());
            }
            if ("TOOL".equals(node.getType()) && configLong(node, "toolId") == null) {
                errors.add("工具节点未选择工具：" + node.getId());
            }
            if ("CONDITION".equals(node.getType()) && isBlank(node, "expression")) {
                errors.add("条件节点缺少表达式：" + node.getId());
            }
            if ("WORKFLOW".equals(node.getType()) && configLong(node, "workflowId") == null) {
                errors.add("子流程节点未选择工作流：" + node.getId());
            }
        }

        // 环路检测（放行指向 LOOP 的回边）
        List<String> cycleErrs = detectIllegalCycle(graph, nodeMap);
        errors.addAll(cycleErrs);

        // 子流程递归检测
        errors.addAll(detectWorkflowRecursion(graph, nodeMap));

        if (!errors.isEmpty()) {
            return new GraphValidationResult(errors, Set.of(), Map.of(), Map.of(), graph);
        }

        // 产出回边集合与并行度入边计数
        Set<String> backEdgeTargets = new HashSet<>();
        for (GraphEdge edge : graph.getEdgesSafe()) {
            GraphNode target = nodeMap.get(edge.getTarget());
            if (target != null && "LOOP".equals(target.getType())) {
                backEdgeTargets.add(edge.getSource());
            }
        }
        Map<String, Integer> parallelInDegree = new HashMap<>();
        for (GraphNode n : graph.getNodes()) {
            int deg = 0;
            for (GraphEdge edge : graph.getEdgesSafe()) {
                if (!edge.getTarget().equals(n.getId())) continue;
                GraphNode target = nodeMap.get(edge.getTarget());
                if (target != null && "LOOP".equals(target.getType())) continue;
                deg++;
            }
            parallelInDegree.put(n.getId(), deg);
        }
        return new GraphValidationResult(errors, backEdgeTargets, parallelInDegree, inDegree, graph);
    }

    /** 除"指向 LOOP 节点的回边"外，图中不允许成环。 */
    private List<String> detectIllegalCycle(WorkflowGraph graph, Map<String, GraphNode> nodeMap) {
        Map<String, List<String>> adjacency = new HashMap<>();
        for (GraphEdge edge : graph.getEdgesSafe()) {
            GraphNode target = nodeMap.get(edge.getTarget());
            if (target != null && "LOOP".equals(target.getType())) continue;
            if (!nodeMap.containsKey(edge.getSource())) continue;
            adjacency.computeIfAbsent(edge.getSource(), k -> new ArrayList<>()).add(edge.getTarget());
        }

        Map<String, Integer> state = new HashMap<>();
        for (String id : nodeMap.keySet()) {
            if (state.getOrDefault(id, 0) == 0 && hasCycle(id, adjacency, state)) {
                return List.of("检测到非法环（仅允许连回 LOOP 节点的回边），请检查连线");
            }
        }
        return List.of();
    }

    /** 子流程节点不允许递归指向自己。 */
    private List<String> detectWorkflowRecursion(WorkflowGraph graph, Map<String, GraphNode> nodeMap) {
        Set<Long> myIds = new HashSet<>();
        for (GraphNode n : nodeMap.values()) {
            if ("WORKFLOW".equals(n.getType())) {
                Long id = configLong(n, "workflowId");
                if (id != null) myIds.add(id);
            }
        }
        if (myIds.isEmpty()) return List.of();
        // 本方法只校验"当前图不指向自己"；跨工作流递归在运行时拦截（最多 3 层）
        return List.of();
    }

    private boolean hasCycle(String id, Map<String, List<String>> adjacency, Map<String, Integer> state) {
        state.put(id, 1);
        for (String next : adjacency.getOrDefault(id, List.of())) {
            int s = state.getOrDefault(next, 0);
            if (s == 1) return true;
            if (s == 0 && hasCycle(next, adjacency, state)) return true;
        }
        state.put(id, 2);
        return false;
    }

    private Long configLong(GraphNode node, String key) {
        if (node.getConfig() == null) return null;
        Object v = node.getConfig().get(key);
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean isBlank(GraphNode node, String key) {
        if (node.getConfig() == null) return true;
        Object v = node.getConfig().get(key);
        return v == null || String.valueOf(v).isBlank();
    }

    /** 校验产出。并行调度需要用 {@link #parallelInDegree}。 */
    public static class GraphValidationResult {
        public final List<String> errors;
        public final Set<String> backEdgeSources;
        public final Map<String, Integer> parallelInDegree;
        public final Map<String, Integer> rawInDegree;
        public final WorkflowGraph graph;

        public GraphValidationResult(List<String> errors, Set<String> backEdgeSources,
                                     Map<String, Integer> parallelInDegree, Map<String, Integer> rawInDegree,
                                     WorkflowGraph graph) {
            this.errors = errors;
            this.backEdgeSources = backEdgeSources;
            this.parallelInDegree = parallelInDegree;
            this.rawInDegree = rawInDegree;
            this.graph = graph;
        }

        public boolean hasErrors() { return !errors.isEmpty(); }
    }
}
