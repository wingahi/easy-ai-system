package com.wgh.rag.workflow.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.kb.service.RagRetrieveService;
import com.wgh.rag.llm.factory.ModelCacheManager;
import com.wgh.rag.tool.entity.ToolConfig;
import com.wgh.rag.workflow.dto.*;
import com.wgh.rag.workflow.entity.WorkflowNodeRun;
import com.wgh.rag.workflow.entity.WorkflowRun;
import com.wgh.rag.workflow.mapper.WorkflowNodeRunMapper;
import com.wgh.rag.workflow.mapper.WorkflowRunMapper;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/**
 * 工作流执行引擎 —— 串行工作队列 + DAG 推进 + HITL 断点恢复。
 *
 * 设计要点：
 * - 每次节点执行结束立即落库（先落库、再发 SSE），刷新页面不丢进度；
 * - 仅允许"指向 LOOP 节点的回边"，其余环在保存阶段被 GraphValidator 拒绝；
 * - 循环体通过回到 LOOP 节点自增轮次实现，最多 maxIterations 轮。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowEngine {

    private static final int MAX_TOTAL_NODE_EXECUTIONS = 1000;

    private final WorkflowRunMapper runMapper;
    private final WorkflowNodeRunMapper nodeRunMapper;
    private final VariableResolver variableResolver;
    private final ConditionEvaluator conditionEvaluator;
    private final ModelCacheManager modelCacheManager;
    private final RagRetrieveService ragRetrieveService;
    private final ToolNodeInvoker toolNodeInvoker;
    private final ObjectMapper objectMapper;

    // ==================== 入口 ====================

    /** 首次执行 */
    public void run(WorkflowRun run, WorkflowGraph graph, String input,
                    BiConsumer<String, Map<String, Object>> sink) {
        RunContext ctx = new RunContext();
        ctx.getVars().put("input", input == null ? "" : input);
        ctx.getVars().put("lastOutput", "");

        GraphNode start = startNode(graph);
        List<String> frontier = new ArrayList<>(List.of(start.getId()));
        executeLoop(run, graph, ctx, frontier, sink);
    }

    /** 从 HITL 断点恢复 */
    public void resume(WorkflowRun run, WorkflowGraph graph, RunContext ctx,
                       BiConsumer<String, Map<String, Object>> sink) {
        if (ctx.getPendingNodeIds() == null || ctx.getPendingNodeIds().isEmpty()) {
            finishRun(run, "FAILED", "HITL 恢复失败：没有可执行的后续节点", sink);
            sink.accept("workflow_failed", Map.of(
                    "runId", run.getId(), "error", "HITL 恢复失败：没有可执行的后续节点"));
            return;
        }
        executeLoop(run, graph, ctx, new ArrayList<>(ctx.getPendingNodeIds()), sink);
    }

    // ==================== 主循环 ====================

    private void executeLoop(WorkflowRun run, WorkflowGraph graph, RunContext ctx,
                             List<String> initialFrontier,
                             BiConsumer<String, Map<String, Object>> sink) {
        Map<String, GraphNode> nodeMap = graph.getNodes().stream()
                .collect(Collectors.toMap(GraphNode::getId, n -> n, (a, b) -> a));
        Map<String, List<GraphEdge>> outEdges = new HashMap<>();
        for (GraphEdge edge : graph.getEdgesSafe()) {
            outEdges.computeIfAbsent(edge.getSource(), k -> new ArrayList<>()).add(edge);
        }

        Deque<String> frontier = new ArrayDeque<>(initialFrontier);
        int guard = 0;
        int executed = 0;

        while (!frontier.isEmpty()) {
            if (++guard > MAX_TOTAL_NODE_EXECUTIONS) {
                finishRun(run, "FAILED", "超过最大节点执行次数 " + MAX_TOTAL_NODE_EXECUTIONS, sink);
                sink.accept("workflow_failed", Map.of(
                        "runId", run.getId(), "error", "超过最大节点执行次数"));
                return;
            }

            WorkflowRun latest = runMapper.selectById(run.getId());
            if (latest == null || "CANCELLED".equals(latest.getStatus())) {
                sink.accept("workflow_cancelled", Map.of("runId", run.getId()));
                return;
            }

            String nodeId = frontier.poll();
            GraphNode node = nodeMap.get(nodeId);
            if (node == null) {
                failRun(run, nodeId, "节点不存在：" + nodeId, sink);
                return;
            }

            // ---------- 循环回边：仅自增轮次，不重复执行节点体 ----------
            if ("LOOP".equals(node.getType()) && ctx.getLoopIterations().containsKey(nodeId)) {
                int prev = ctx.getLoopIterations().get(nodeId);
                int max = intConfig(node, "maxIterations", 3);
                boolean shouldBreak = prev >= max || evalBreakCondition(node, ctx);
                int iteration = prev;
                if (!shouldBreak) {
                    iteration = prev + 1;
                    ctx.getLoopIterations().put(nodeId, iteration);
                    ctx.setCurrentLoopIteration(iteration);
                }
                Map<String, Object> output = new LinkedHashMap<>();
                output.put("iteration", iteration);
                ctx.getNodeOutputs().put(nodeId, output);

                appendNodeRun(run, node, iteration, output, 0L, "SUCCESS", null);
                persistContext(run, ctx, "RUNNING");
                executed++;
                sink.accept("node_output", event(run, node, output, 0L, iteration));

                frontier.addAll(branchTargets(outEdges.get(nodeId), shouldBreak ? "done" : "loop"));
                continue;
            }

            // ---------- HITL：暂停并持久化断点 ----------
            if ("HITL".equals(node.getType())) {
                returnFromHitl(run, node, ctx, outEdges.get(nodeId), sink);
                return;
            }

            // ---------- 普通节点 ----------
            sink.accept("node_start", Map.of(
                    "runId", run.getId(),
                    "nodeId", node.getId(),
                    "nodeType", node.getType(),
                    "nodeName", nameOf(node),
                    "iteration", ctx.getCurrentLoopIteration()));

            WorkflowNodeRun nr = insertNodeRun(run, node, ctx.getCurrentLoopIteration());
            long t0 = System.currentTimeMillis();
            NodeResult result;
            try {
                result = executeNode(node, ctx, run);
            } catch (Exception e) {
                long duration = System.currentTimeMillis() - t0;
                nr.setStatus("FAILED");
                nr.setEndTime(LocalDateTime.now());
                nr.setDurationMs(duration);
                nr.setErrorMessage(e.getMessage());
                nodeRunMapper.updateById(nr);

                Map<String, Object> errPayload = new LinkedHashMap<>();
                errPayload.put("runId", run.getId());
                errPayload.put("nodeId", node.getId());
                errPayload.put("nodeName", nameOf(node));
                errPayload.put("error", e.getMessage());
                sink.accept("node_error", errPayload);

                if ("CONTINUE".equalsIgnoreCase(node.getOnError())) {
                    log.warn("节点 {} 失败但配置为 CONTINUE，继续执行", node.getId(), e);
                    Map<String, Object> failed = new LinkedHashMap<>();
                    failed.put("error", e.getMessage());
                    ctx.getNodeOutputs().put(node.getId(), failed);
                    persistContext(run, ctx, "RUNNING");
                    frontier.addAll(allTargets(outEdges.get(node.getId())));
                    continue;
                }
                failRun(run, node.getId(), "节点「" + nameOf(node) + "」执行失败：" + e.getMessage(), sink);
                return;
            }

            long duration = System.currentTimeMillis() - t0;
            ctx.getNodeOutputs().put(node.getId(), result.getOutput());
            Object text = result.getOutput().get("text");
            if (text != null) ctx.getVars().put("lastOutput", text);

            nr.setStatus("SUCCESS");
            nr.setOutputJson(toJson(result.getOutput()));
            nr.setEndTime(LocalDateTime.now());
            nr.setDurationMs(duration);
            nodeRunMapper.updateById(nr);
            persistContext(run, ctx, "RUNNING");
            executed++;
            run.setExecutedNodes(executed);

            sink.accept("node_output", event(run, node, result.getOutput(), duration,
                    ctx.getCurrentLoopIteration()));

            if ("END".equals(node.getType())) {
                String output = text != null ? String.valueOf(text) : "";
                finishRun(run, "COMPLETED", null, sink, output, executed);
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("runId", run.getId());
                payload.put("output", output);
                payload.put("durationMs", elapsed(run));
                payload.put("executedNodes", executed);
                sink.accept("workflow_complete", payload);
                return;
            }

            frontier.addAll(selectNext(node, result, ctx, outEdges.get(node.getId())));
        }

        // 队列耗尽但没有走到 END
        finishRun(run, "COMPLETED", null, sink, String.valueOf(ctx.getVars().getOrDefault("lastOutput", "")), executed);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", run.getId());
        payload.put("output", ctx.getVars().getOrDefault("lastOutput", ""));
        payload.put("durationMs", elapsed(run));
        payload.put("executedNodes", executed);
        sink.accept("workflow_complete", payload);
    }

    // ==================== 节点执行 ====================

    private NodeResult executeNode(GraphNode node, RunContext ctx, WorkflowRun run) throws Exception {
        String type = node.getType();
        Map<String, Object> cfg = node.getConfig() != null ? node.getConfig() : Map.of();

        return switch (type) {
            case "START" -> {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("text", ctx.getVars().getOrDefault("input", ""));
                yield NodeResult.builder().success(true).output(out).build();
            }
            case "LLM" -> executeLlm(cfg, ctx);
            case "RETRIEVAL" -> executeRetrieval(cfg, ctx);
            case "TOOL" -> executeTool(cfg, ctx);
            case "CONDITION" -> {
                String expr = variableResolver.render(str(cfg.get("expression")), ctx);
                boolean result = conditionEvaluator.evaluate(expr);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("result", result);
                out.put("expression", expr);
                yield NodeResult.builder().success(true).output(out).build();
            }
            case "LOOP" -> {
                int max = intConfig(node, "maxIterations", 3);
                // 轮次从 1 开始：第 1 轮循环体使用 iteration=1，控制台/变量语义直观
                ctx.getLoopIterations().put(node.getId(), 1);
                ctx.setCurrentLoopIteration(1);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("iteration", 1);
                out.put("maxIterations", max);
                yield NodeResult.builder().success(true).output(out).build();
            }
            case "VARIABLE" -> executeVariable(cfg, ctx);
            case "END" -> {
                String template = str(cfg.get("output"));
                String text = template.isBlank()
                        ? String.valueOf(ctx.getVars().getOrDefault("lastOutput", ""))
                        : variableResolver.render(template, ctx);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("text", text);
                yield NodeResult.builder().success(true).output(out).build();
            }
            default -> throw new IllegalStateException("不支持的节点类型：" + type);
        };
    }

    private NodeResult executeLlm(Map<String, Object> cfg, RunContext ctx) {
        Long modelId = asLong(cfg.get("modelId"));
        if (modelId == null) throw new IllegalArgumentException("LLM 节点缺少 modelId");

        String systemPrompt = variableResolver.render(str(cfg.get("systemPrompt")), ctx);
        String prompt = variableResolver.render(str(cfg.get("prompt")), ctx);
        if (prompt.isBlank()) prompt = String.valueOf(ctx.getVars().getOrDefault("lastOutput", ""));

        List<ChatMessage> messages = new ArrayList<>();
        if (!systemPrompt.isBlank()) messages.add(SystemMessage.from(systemPrompt));
        messages.add(UserMessage.from(prompt));

        ChatModel model = modelCacheManager.getChatModel(modelId);
        ChatResponse response = model.chat(ChatRequest.builder().messages(messages).build());
        String text = response.aiMessage() != null ? response.aiMessage().text() : "";

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("text", text != null ? text : "");
        return NodeResult.builder().success(true).output(out).build();
    }

    private NodeResult executeRetrieval(Map<String, Object> cfg, RunContext ctx) {
        List<Long> kbIds = asLongList(cfg.get("kbIds"));
        if (kbIds.isEmpty()) throw new IllegalArgumentException("知识库节点未选择知识库");
        String query = variableResolver.render(str(cfg.get("query")), ctx);
        if (query.isBlank()) query = String.valueOf(ctx.getVars().getOrDefault("input", ""));
        int topK = asInt(cfg.get("topK"), 5);

        List<EmbeddingMatch<TextSegment>> matches = ragRetrieveService.searchMultiple(kbIds, query);
        if (matches == null) matches = List.of();
        if (matches.size() > topK) matches = matches.subList(0, topK);

        StringBuilder text = new StringBuilder();
        List<Map<String, Object>> sources = new ArrayList<>();
        int idx = 0;
        for (EmbeddingMatch<TextSegment> match : matches) {
            idx++;
            String content = match.embedded() != null ? match.embedded().text() : "";
            String fileName = null;
            Integer chunkIndex = null;
            if (match.embedded() != null && match.embedded().metadata() != null) {
                fileName = match.embedded().metadata().getString("fileName");
                chunkIndex = match.embedded().metadata().getInteger("chunkIndex");
            }
            text.append("【片段 ").append(idx).append("】")
                    .append(fileName != null ? fileName : "未知来源")
                    .append(chunkIndex != null ? "#" + chunkIndex : "")
                    .append("\n").append(content).append("\n\n");

            Map<String, Object> src = new LinkedHashMap<>();
            src.put("fileName", fileName);
            src.put("chunkIndex", chunkIndex);
            src.put("score", match.score());
            sources.add(src);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("text", text.toString().trim());
        out.put("sources", sources);
        out.put("count", sources.size());
        return NodeResult.builder().success(true).output(out).build();
    }

    private NodeResult executeTool(Map<String, Object> cfg, RunContext ctx) throws Exception {
        Long toolId = asLong(cfg.get("toolId"));
        if (toolId == null) throw new IllegalArgumentException("工具节点缺少 toolId");

        Object rawArgs = cfg.get("arguments");
        Map<String, Object> args = new LinkedHashMap<>();
        if (rawArgs instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object v = entry.getValue();
                args.put(String.valueOf(entry.getKey()),
                        v instanceof String s ? variableResolver.render(s, ctx) : v);
            }
        }

        String text = toolNodeInvoker.invoke(toolId, args);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("text", text);
        out.put("success", text == null || !text.startsWith("Error:"));
        return NodeResult.builder().success(true).output(out).build();
    }

    private NodeResult executeVariable(Map<String, Object> cfg, RunContext ctx) {
        Object raw = cfg.get("assignments");
        Map<String, Object> out = new LinkedHashMap<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    String name = String.valueOf(map.get("name"));
                    Object value = map.get("value");
                    String rendered = value instanceof String s ? variableResolver.render(s, ctx)
                            : String.valueOf(value);
                    ctx.getVars().put(name, rendered);
                    out.put(name, rendered);
                }
            }
        }
        return NodeResult.builder().success(true).output(out).build();
    }

    // ==================== HITL ====================

    private void returnFromHitl(WorkflowRun run, GraphNode node, RunContext ctx,
                                List<GraphEdge> edges, BiConsumer<String, Map<String, Object>> sink) {
        String prompt = variableResolver.render(str(node.getConfig().get("prompt")), ctx);

        WorkflowNodeRun nr = new WorkflowNodeRun();
        nr.setRunId(run.getId());
        nr.setNodeId(node.getId());
        nr.setNodeType(node.getType());
        nr.setNodeName(nameOf(node));
        nr.setStatus("HITL_PENDING");
        nr.setIteration(ctx.getCurrentLoopIteration());
        nr.setInputJson(toJson(Map.of("prompt", prompt)));
        nr.setStartTime(LocalDateTime.now());
        nodeRunMapper.insert(nr);

        ctx.setPendingNodeIds(allTargets(edges));
        persistContext(run, ctx, "PAUSED");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", run.getId());
        payload.put("nodeRunId", nr.getId());
        payload.put("nodeId", node.getId());
        payload.put("nodeName", nameOf(node));
        payload.put("prompt", prompt);
        sink.accept("node_hitl_pending", payload);
        throw new PauseSignal("HITL 暂停：" + node.getId());
    }

    // ==================== 分支选择 ====================

    private List<String> selectNext(GraphNode node, NodeResult result, RunContext ctx,
                                    List<GraphEdge> edges) {
        if ("CONDITION".equals(node.getType())) {
            Object v = result.getOutput().get("result");
            List<String> picked = branchTargets(edges, String.valueOf(v));
            return picked.isEmpty() ? branchTargets(edges, "default") : picked;
        }
        if ("LOOP".equals(node.getType())) {
            int max = intConfig(node, "maxIterations", 3);
            return branchTargets(edges, max <= 0 ? "done" : "loop");
        }
        return allTargets(edges);
    }

    /** 按 handle 取目标；空 handle 视为 default。 */
    private List<String> branchTargets(List<GraphEdge> edges, String handle) {
        if (edges == null) return new ArrayList<>();
        List<String> targets = new ArrayList<>();
        for (GraphEdge edge : edges) {
            String h = edge.getSourceHandle();
            boolean match = (h == null || h.isBlank() || "default".equals(h))
                    ? "default".equals(handle)
                    : h.equals(handle);
            if (match) targets.add(edge.getTarget());
        }
        return targets;
    }

    private List<String> allTargets(List<GraphEdge> edges) {
        if (edges == null) return new ArrayList<>();
        return edges.stream().map(GraphEdge::getTarget).distinct().collect(Collectors.toList());
    }

    // ==================== 持久化 / 事件 ====================

    private WorkflowNodeRun insertNodeRun(WorkflowRun run, GraphNode node, int iteration) {
        WorkflowNodeRun nr = new WorkflowNodeRun();
        nr.setRunId(run.getId());
        nr.setNodeId(node.getId());
        nr.setNodeType(node.getType());
        nr.setNodeName(nameOf(node));
        nr.setStatus("RUNNING");
        nr.setIteration(iteration);
        nr.setInputJson(toJson(node.getConfig()));
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

    private void persistContext(WorkflowRun run, RunContext ctx, String status) {
        run.setContextJson(toJson(ctx));
        run.setStatus(status);
        run.setUpdateTime(LocalDateTime.now());
        runMapper.updateById(run);
    }

    private void failRun(WorkflowRun run, String nodeId, String error,
                         BiConsumer<String, Map<String, Object>> sink) {
        finishRun(run, "FAILED", error, sink);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", run.getId());
        payload.put("nodeId", nodeId);
        payload.put("error", error);
        sink.accept("workflow_failed", payload);
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
                .filter(n -> "START".equals(n.getType()))
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

    private String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private Long asLong(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private int asInt(Object v, int defaultValue) {
        Long l = asLong(v);
        return l == null ? defaultValue : l.intValue();
    }

    private int intConfig(GraphNode node, String key, int defaultValue) {
        return asInt(node.getConfig() != null ? node.getConfig().get(key) : null, defaultValue);
    }

    private List<Long> asLongList(Object v) {
        List<Long> ids = new ArrayList<>();
        if (v == null) return ids;
        if (v instanceof List<?> list) {
            for (Object o : list) {
                Long id = asLong(o);
                if (id != null) ids.add(id);
            }
            return ids;
        }
        for (String part : String.valueOf(v).split(",")) {
            Long id = asLong(part.trim());
            if (id != null) ids.add(id);
        }
        return ids;
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
