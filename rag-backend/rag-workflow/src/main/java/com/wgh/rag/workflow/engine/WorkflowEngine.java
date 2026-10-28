package com.wgh.rag.workflow.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.kb.service.RagRetrieveService;
import com.wgh.rag.llm.factory.ModelCacheManager;
import com.wgh.rag.tool.entity.ToolConfig;
import com.wgh.rag.workflow.dto.*;
import com.wgh.rag.workflow.entity.Workflow;
import com.wgh.rag.workflow.entity.WorkflowNodeRun;
import com.wgh.rag.workflow.entity.WorkflowRun;
import com.wgh.rag.workflow.mapper.WorkflowMapper;
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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
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

    private final WorkflowRunMapper runMapper;
    private final WorkflowMapper workflowMapper;
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
                    GraphValidator.GraphValidationResult validation,
                    BiConsumer<String, Map<String, Object>> sink) {
        RunContext ctx = new RunContext();
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
            finishRun(run, "FAILED", "HITL 恢复失败：没有可执行的后续节点", sink);
            sink.accept("workflow_failed", Map.of(
                    "runId", run.getId(), "error", "HITL 恢复失败：没有可执行的后续节点"));
            return;
        }
        executeLoop(run, graph, validation, ctx, new ArrayList<>(ctx.getPendingNodeIds()), sink, 1);
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
        subRun.setStatus("RUNNING");
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
        Map<String, GraphNode> nodeMap = graph.getNodes().stream()
                .collect(Collectors.toMap(GraphNode::getId, n -> n, (a, b) -> a));
        Map<String, List<GraphEdge>> outEdges = new HashMap<>();
        for (GraphEdge edge : graph.getEdgesSafe()) {
            outEdges.computeIfAbsent(edge.getSource(), k -> new ArrayList<>()).add(edge);
        }

        // 初始化入边等待计数器（使用 GraphValidator 产出的 parallelInDegree）
        Map<String, Integer> pendingIn = new ConcurrentHashMap<>(validation.parallelInDegree);
        // resume 时 pendingNodeIds 可能有多个，强制这些节点 pendingIn=0 让它们入队
        for (String id : initialFrontier) {
            pendingIn.put(id, 0);
        }

        // 共享状态
        Deque<String> frontier = new ArrayDeque<>(initialFrontier);
        AtomicInteger guard = new AtomicInteger(0);
        AtomicInteger executed = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(1);
        // 已提交到执行队列的节点（防重复提交）
        Set<String> submitted = ConcurrentHashMap.newKeySet();

        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                WORKER_COUNT, WORKER_COUNT, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(500),
                r -> new Thread(r, "wf-parallel-worker-" + r.hashCode()),
                new ThreadPoolExecutor.AbortPolicy());

        try {
            // 调度线程：持续从 frontier 取节点、判断 pendingIn、提交给 worker
            Thread dispatcher = new Thread(() -> {
                try {
                    while (true) {
                        String nodeId;
                        synchronized (frontier) {
                            while (frontier.isEmpty() && submitted.isEmpty()) {
                                // 所有节点都执行完了
                                return;
                            }
                            nodeId = frontier.pollFirst();
                        }
                        if (nodeId == null) {
                            Thread.sleep(20);
                            continue;
                        }
                        int remain = pendingIn.getOrDefault(nodeId, 0);
                        if (remain > 0) {
                            // 还有入边未就绪，放回队列尾部等待（另一个线程可能已在处理入边）
                            synchronized (frontier) { frontier.offerLast(nodeId); }
                            continue;
                        }
                        if (!submitted.add(nodeId)) continue; // 已提交过

                        if (guard.incrementAndGet() > MAX_TOTAL_NODE_EXECUTIONS) {
                            finishRun(run, "FAILED", "超过最大节点执行次数 " + MAX_TOTAL_NODE_EXECUTIONS, sink);
                            sink.accept("workflow_failed", Map.of(
                                    "runId", run.getId(), "error", "超过最大节点执行次数"));
                            return;
                        }

                        // worker 执行节点
                        executor.submit(() -> executeOneNode(run, graph, nodeMap, outEdges, nodeId,
                                ctx, sink, pendingIn, frontier, submitted, guard, executed,
                                latch, nestingDepth, validation));
                    }
                } catch (Throwable t) {
                    log.error("工作流调度线程异常", t);
                }
            }, "wf-dispatcher-" + run.getId());
            dispatcher.start();
            try {
                dispatcher.join(5 * 60 * 1000L); // 最多等 5 分钟
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("工作流调度线程被中断 runId={}", run.getId());
            }
        } finally {
            executor.shutdownNow();
            latch.countDown();
        }
    }

    private void executeOneNode(WorkflowRun run, WorkflowGraph graph,
                                Map<String, GraphNode> nodeMap, Map<String, List<GraphEdge>> outEdges,
                                String nodeId, RunContext ctx,
                                BiConsumer<String, Map<String, Object>> sink,
                                Map<String, Integer> pendingIn, Deque<String> frontier,
                                Set<String> submitted, AtomicInteger guard, AtomicInteger executed,
                                CountDownLatch latch, int nestingDepth,
                                GraphValidator.GraphValidationResult validation) {
        try {
            // 周期检查取消状态
            WorkflowRun latest = runMapper.selectById(run.getId());
            if (latest == null || "CANCELLED".equals(latest.getStatus())) {
                sink.accept("workflow_cancelled", Map.of("runId", run.getId()));
                return;
            }

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
                executed.incrementAndGet();
                sink.accept("node_output", event(run, node, output, 0L, iteration));

                activateSuccessors(node, outEdges, shouldBreak ? "done" : "loop",
                        pendingIn, frontier, submitted);
                return;
            }

            // ---------- HITL：暂停并持久化断点 ----------
            if ("HITL".equals(node.getType())) {
                returnFromHitl(run, node, ctx, outEdges.get(nodeId), sink);
                throw new PauseSignal("HITL 暂停：" + nodeId);
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
                result = executeNode(node, ctx, run, nestingDepth);
            } catch (PauseSignal ps) {
                // HITL 已在 returnFromHitl 内部处理并持久化，直接向上抛出给调度器
                throw ps;
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
                    activateAllSuccessors(node, outEdges, pendingIn, frontier, submitted);
                    return;
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
            executed.incrementAndGet();
            run.setExecutedNodes(executed.get());

            sink.accept("node_output", event(run, node, result.getOutput(), duration,
                    ctx.getCurrentLoopIteration()));

            if ("END".equals(node.getType())) {
                String outText = text != null ? String.valueOf(text) : "";
                finishRun(run, "COMPLETED", null, sink, outText, executed.get());
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("runId", run.getId());
                payload.put("output", outText);
                payload.put("durationMs", elapsed(run));
                payload.put("executedNodes", executed.get());
                sink.accept("workflow_complete", payload);
                return;
            }

            if ("CONDITION".equals(node.getType())) {
                boolean bool = Boolean.TRUE.equals(result.getOutput().get("result"));
                activateSuccessors(node, outEdges, String.valueOf(bool), pendingIn, frontier, submitted);
            } else if ("LOOP".equals(node.getType())) {
                int max = intConfig(node, "maxIterations", 3);
                activateSuccessors(node, outEdges, max <= 0 ? "done" : "loop", pendingIn, frontier, submitted);
            } else {
                activateAllSuccessors(node, outEdges, pendingIn, frontier, submitted);
            }
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
            if ("CONDITION".equals(node.getType()) || "LOOP".equals(node.getType())) {
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

    private NodeResult executeNode(GraphNode node, RunContext ctx, WorkflowRun run, int nestingDepth) throws Exception {
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
            case "WORKFLOW" -> executeSubWorkflow(cfg, ctx, run, nestingDepth);
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

    private NodeResult executeSubWorkflow(Map<String, Object> cfg, RunContext ctx, WorkflowRun parentRun, int nestingDepth) throws Exception {
        Long subId = asLong(cfg.get("workflowId"));
        if (subId == null) throw new IllegalArgumentException("子流程节点缺少 workflowId");

        Workflow subWorkflow = workflowMapper.selectById(subId);
        if (subWorkflow == null) throw new IllegalStateException("子流程不存在：" + subId);

        GraphValidator.GraphValidationResult vr = new GraphValidator()
                .validate(parseGraph(subWorkflow.getGraphJson()));
        if (vr.hasErrors()) throw new IllegalStateException("子流程图校验失败：" + String.join("；", vr.errors));

        String inputTemplate = str(cfg.get("inputTemplate"));
        String input;
        if (inputTemplate.isBlank()) {
            input = String.valueOf(ctx.getVars().getOrDefault("lastOutput",
                    ctx.getVars().getOrDefault("input", "")));
        } else {
            input = variableResolver.render(inputTemplate, ctx);
        }

        WorkflowRun subRun = runSubWorkflow(subWorkflow, vr.graph, input, vr, nestingDepth + 1);

        String output;
        String outTemplate = str(cfg.get("outputTemplate"));
        if (outTemplate.isBlank()) {
            output = subRun.getOutputText() != null ? subRun.getOutputText() : "";
        } else {
            // 把子输出暴露到临时变量里再渲染
            ctx.getVars().put("_subOutput_" + subId, subRun.getOutputText());
            output = variableResolver.render(outTemplate, ctx);
        }

        if ("FAILED".equals(subRun.getStatus())) {
            throw new IllegalStateException("子流程执行失败：" + subRun.getErrorMessage());
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("text", output);
        out.put("subRunId", subRun.getId());
        out.put("subStatus", subRun.getStatus());
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

    private WorkflowGraph parseGraph(String json) {
        if (json == null || json.isBlank()) throw new IllegalStateException("图为空");
        try {
            return objectMapper.readValue(json, WorkflowGraph.class);
        } catch (Exception e) {
            throw new IllegalStateException("图解析失败：" + e.getMessage());
        }
    }
}
