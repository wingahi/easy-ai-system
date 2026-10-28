package com.wgh.rag.workflow.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.kb.service.RagRetrieveService;
import com.wgh.rag.llm.factory.ModelCacheManager;
import com.wgh.rag.workflow.dto.GraphEdge;
import com.wgh.rag.workflow.dto.GraphNode;
import com.wgh.rag.workflow.dto.RunContext;
import com.wgh.rag.workflow.dto.WorkflowGraph;
import com.wgh.rag.workflow.entity.Workflow;
import com.wgh.rag.workflow.entity.WorkflowNodeRun;
import com.wgh.rag.workflow.entity.WorkflowRun;
import com.wgh.rag.workflow.engine.executor.ConditionNodeExecutor;
import com.wgh.rag.workflow.engine.executor.EndNodeExecutor;
import com.wgh.rag.workflow.engine.executor.LlmNodeExecutor;
import com.wgh.rag.workflow.engine.executor.LoopNodeExecutor;
import com.wgh.rag.workflow.engine.executor.NodeExecutorRegistry;
import com.wgh.rag.workflow.engine.executor.RetrievalNodeExecutor;
import com.wgh.rag.workflow.engine.executor.StartNodeExecutor;
import com.wgh.rag.workflow.engine.executor.ToolNodeExecutor;
import com.wgh.rag.workflow.engine.executor.VariableNodeExecutor;
import com.wgh.rag.workflow.engine.executor.WorkflowNodeExecutor;
import com.wgh.rag.workflow.mapper.WorkflowMapper;
import com.wgh.rag.workflow.mapper.WorkflowNodeRunMapper;
import com.wgh.rag.workflow.mapper.WorkflowRunMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WorkflowEngine 行为单元测试 —— 重构前补齐的行为安全网。
 * 覆盖：SSE 事件序列、变量渲染、CONDITION 路由、LOOP 回边、HITL 暂停/恢复、
 * 子流程嵌套、并行 fan-in、失败策略（STOP/CONTINUE）、未知类型报错。
 * 依赖全部 Mockito mock，不启动 Spring 容器。
 */
@ExtendWith(MockitoExtension.class)
@Timeout(30)
class WorkflowEngineTest {

    @Mock
    private WorkflowRunMapper runMapper;
    @Mock
    private WorkflowMapper workflowMapper;
    @Mock
    private WorkflowNodeRunMapper nodeRunMapper;
    @Mock
    private ModelCacheManager modelCacheManager;
    @Mock
    private RagRetrieveService ragRetrieveService;
    @Mock
    private ToolNodeInvoker toolNodeInvoker;
    @Mock
    private ChatModel chatModel;

    private final VariableResolver variableResolver = new VariableResolver();
    private final ConditionEvaluator conditionEvaluator = new ConditionEvaluator();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private WorkflowEngine engine;
    private ConcurrentLinkedQueue<Map.Entry<String, Map<String, Object>>> events;

    @BeforeEach
    void setUp() {
        // 子流程执行器懒解析引擎（测试内手工组装，等价于 Spring ObjectProvider 注入）
        org.springframework.beans.factory.ObjectProvider<WorkflowEngine> engineProvider =
                new org.springframework.beans.factory.ObjectProvider<>() {
                    @Override
                    public WorkflowEngine getObject() {
                        return engine;
                    }

                    @Override
                    public WorkflowEngine getObject(Object... args) {
                        return engine;
                    }
                };
        NodeExecutorRegistry registry = new NodeExecutorRegistry(List.of(
                new StartNodeExecutor(),
                new LlmNodeExecutor(variableResolver, modelCacheManager),
                new RetrievalNodeExecutor(variableResolver, ragRetrieveService),
                new ToolNodeExecutor(variableResolver, toolNodeInvoker),
                new WorkflowNodeExecutor(variableResolver, workflowMapper, objectMapper, engineProvider),
                new ConditionNodeExecutor(variableResolver, conditionEvaluator),
                new LoopNodeExecutor(),
                new VariableNodeExecutor(variableResolver),
                new EndNodeExecutor(variableResolver)));
        engine = new WorkflowEngine(runMapper, nodeRunMapper, variableResolver,
                conditionEvaluator, registry, objectMapper);
        events = new ConcurrentLinkedQueue<>();

        lenient().when(runMapper.selectById(any())).thenAnswer(inv -> {
            WorkflowRun r = new WorkflowRun();
            r.setId(inv.getArgument(0));
            r.setStatus("RUNNING");
            r.setExecutedNodes(0);
            r.setStartTime(LocalDateTime.now());
            return r;
        });
        lenient().when(runMapper.updateById(any(WorkflowRun.class))).thenReturn(1);
        lenient().when(runMapper.insert(any(WorkflowRun.class))).thenAnswer(inv -> {
            WorkflowRun r = inv.getArgument(0);
            if (r.getId() == null) r.setId(900L); // 模拟 IdType.AUTO 生成主键
            return 1;
        });
        lenient().when(nodeRunMapper.insert(any(WorkflowNodeRun.class))).thenReturn(1);
        lenient().when(nodeRunMapper.updateById(any(WorkflowNodeRun.class))).thenReturn(1);
    }

    // ==================== 基础链路 ====================

    /** START → END：input 经 lastOutput 传到 END，workflow_complete 携带输出 */
    @Test
    void run_startToEnd_completesWithInputEcho() {
        WorkflowGraph graph = graph(
                List.of(node("s", "START"), node("e", "END")),
                List.of(edge("s", "e")));

        engine.run(newRun(), graph, "你好", validate(graph), this::sink);

        assertThat(eventNames()).containsSubsequence("node_start", "node_output", "node_start", "node_output", "workflow_complete");
        Map<String, Object> complete = lastPayload("workflow_complete");
        assertThat(complete.get("output")).isEqualTo("你好");
        assertThat(lastRunUpdate().getStatus()).isEqualTo("COMPLETED");
    }

    /** VARIABLE 赋值 + END 模板渲染 {{vars.x}} */
    @Test
    void run_variableNode_assignsAndRenders() {
        GraphNode var = node("v", "VARIABLE", Map.of("assignments",
                List.of(Map.of("name", "x", "value", "{{input}}-已处理"))));
        GraphNode end = node("e", "END", Map.of("output", "结果：{{vars.x}}"));
        WorkflowGraph graph = graph(
                List.of(node("s", "START"), var, end),
                List.of(edge("s", "v"), edge("v", "e")));

        engine.run(newRun(), graph, "你好", validate(graph), this::sink);

        assertThat(lastPayload("workflow_complete").get("output")).isEqualTo("结果：你好-已处理");
    }

    /** CONDITION 只激活匹配分支（true 边执行，false 边不执行） */
    @Test
    void run_condition_activatesOnlyMatchingBranch() {
        GraphNode cond = node("c", "CONDITION", Map.of("expression", "{{input}} contains \"ok\""));
        WorkflowGraph graph = graph(
                List.of(node("s", "START"), cond,
                        node("a", "VARIABLE", Map.of("assignments", List.of(Map.of("name", "branch", "value", "A")))),
                        node("b", "VARIABLE", Map.of("assignments", List.of(Map.of("name", "branch", "value", "B")))),
                        node("e1", "END", Map.of("output", "走了A")),
                        node("e2", "END", Map.of("output", "走了B"))),
                List.of(edge("s", "c"), edge("c", "a", "true"), edge("c", "b", "false"),
                        edge("a", "e1"), edge("b", "e2")));

        engine.run(newRun(), graph, "this is ok", validate(graph), this::sink);

        List<String> started = nodeStartIds();
        assertThat(started).contains("c", "a", "e1");
        assertThat(started).doesNotContain("b", "e2");
        assertThat(lastPayload("workflow_complete").get("output")).isEqualTo("走了A");
    }

    /** LLM 节点：渲染 prompt，调用模型，输出写入 lastOutput */
    @Test
    void run_llmNode_callsModelAndPropagatesText() {
        when(modelCacheManager.getChatModel(7L)).thenReturn(chatModel);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(
                ChatResponse.builder().aiMessage(AiMessage.from("模型答案")).build());
        GraphNode llm = node("l", "LLM", Map.of("modelId", 7, "prompt", "{{input}}"));
        WorkflowGraph graph = graph(
                List.of(node("s", "START"), llm, node("e", "END")),
                List.of(edge("s", "l"), edge("l", "e")));

        engine.run(newRun(), graph, "提问", validate(graph), this::sink);

        assertThat(lastPayload("workflow_complete").get("output")).isEqualTo("模型答案");
    }

    /** RETRIEVAL 节点：拼接片段文本 + sources 元数据 */
    @Test
    void run_retrievalNode_buildsTextAndSources() {
        Metadata md = new Metadata();
        md.put("fileName", "手册.pdf");
        md.put("chunkIndex", 3);
        when(ragRetrieveService.searchMultiple(List.of(1L), "查手册")).thenReturn(List.of(
                new EmbeddingMatch<>(0.9, "m1", null, TextSegment.from("片段内容", md))));
        GraphNode ret = node("r", "RETRIEVAL", Map.of("kbIds", List.of(1), "query", "{{input}}", "topK", 5));
        WorkflowGraph graph = graph(
                List.of(node("s", "START"), ret, node("e", "END")),
                List.of(edge("s", "r"), edge("r", "e")));

        engine.run(newRun(), graph, "查手册", validate(graph), this::sink);

        Map<String, Object> output = nodeOutputPayload("r");
        assertThat(output.get("text")).isEqualTo("【片段 1】手册.pdf#3\n片段内容");
        assertThat(output.get("count")).isEqualTo(1);
        assertThat(lastPayload("workflow_complete").get("output")).isEqualTo("【片段 1】手册.pdf#3\n片段内容");
    }

    /** TOOL 节点：参数渲染后调用工具，结果写入 text */
    @Test
    void run_toolNode_invokesTool() {
        when(toolNodeInvoker.invoke(org.mockito.ArgumentMatchers.eq(3L), any())).thenReturn("天气晴");
        GraphNode tool = node("t", "TOOL", Map.of("toolId", 3, "arguments", Map.of("city", "{{input}}")));
        WorkflowGraph graph = graph(
                List.of(node("s", "START"), tool, node("e", "END")),
                List.of(edge("s", "t"), edge("t", "e")));

        engine.run(newRun(), graph, "北京", validate(graph), this::sink);

        assertThat(lastPayload("workflow_complete").get("output")).isEqualTo("天气晴");
    }

    // ==================== HITL ====================

    /** HITL：暂停、持久化 PAUSED、推送 node_hitl_pending，不发 workflow_complete */
    @Test
    void run_hitl_pausesAndPersists() {
        GraphNode hitl = node("h", "HITL", Map.of("prompt", "请确认：{{input}}"));
        WorkflowGraph graph = graph(
                List.of(node("s", "START"), hitl, node("e", "END")),
                List.of(edge("s", "h"), edge("h", "e")));

        engine.run(newRun(), graph, "数据", validate(graph), this::sink);

        Map<String, Object> pending = lastPayload("node_hitl_pending");
        assertThat(pending.get("nodeId")).isEqualTo("h");
        assertThat(pending.get("prompt")).isEqualTo("请确认：数据");
        assertThat(eventNames()).doesNotContain("workflow_complete");
        assertThat(lastRunUpdate().getStatus()).isEqualTo("PAUSED");
    }

    /** HITL 恢复：从 pendingNodeIds 续跑至完成 */
    @Test
    void resume_afterHitl_completes() {
        WorkflowGraph graph = graph(
                List.of(node("s", "START"), node("h", "HITL", Map.of("prompt", "确认?")), node("e", "END")),
                List.of(edge("s", "h"), edge("h", "e")));

        RunContext ctx = new RunContext();
        ctx.getVars().put("input", "数据");
        ctx.getVars().put("lastOutput", "人工输入");
        ctx.getNodeOutputs().put("h", Map.of("text", "人工输入"));
        ctx.setPendingNodeIds(new ArrayList<>(List.of("e")));

        engine.resume(newRun(), graph, validate(graph), ctx, this::sink);

        assertThat(lastPayload("workflow_complete").get("output")).isEqualTo("人工输入");
        assertThat(lastRunUpdate().getStatus()).isEqualTo("COMPLETED");
    }

    /** 恢复时无待执行节点：直接 workflow_failed */
    @Test
    void resume_withoutPendingNodes_fails() {
        WorkflowGraph graph = graph(
                List.of(node("s", "START"), node("e", "END")),
                List.of(edge("s", "e")));

        engine.resume(newRun(), graph, validate(graph), new RunContext(), this::sink);

        assertThat(lastPayload("workflow_failed").get("error")).isEqualTo("HITL 恢复失败：没有可执行的后续节点");
    }

    // ==================== LOOP / 并行 / 子流程 ====================

    /** LOOP：循环体执行 maxIterations 次后走 done 边 */
    @Test
    void run_loop_executesBodyUntilMaxIterations() {
        GraphNode loop = node("lp", "LOOP", Map.of("maxIterations", 2));
        GraphNode body = node("v", "VARIABLE", Map.of("assignments",
                List.of(Map.of("name", "round", "value", "{{loop.iteration}}"))));
        WorkflowGraph graph = graph(
                List.of(node("s", "START"), loop, body, node("e", "END", Map.of("output", "done"))),
                List.of(edge("s", "lp"), edge("lp", "v", "loop"), edge("v", "lp"), edge("lp", "e", "done")));

        engine.run(newRun(), graph, "x", validate(graph), this::sink);

        assertThat(countNodeStarts("v")).isEqualTo(2);
        assertThat(events.stream()
                .filter(ev -> "node_output".equals(ev.getKey()) && "lp".equals(ev.getValue().get("nodeId")))
                .count()).isEqualTo(3);
        assertThat(lastPayload("workflow_complete").get("output")).isEqualTo("done");
    }

    /** 并行 fan-out/fan-in：END 等待两条分支都到达后才执行一次 */
    @Test
    void run_parallelBranches_fanInWaitsAll() {
        GraphNode a = node("a", "VARIABLE", Map.of("assignments", List.of(Map.of("name", "x", "value", "1"))));
        GraphNode b = node("b", "VARIABLE", Map.of("assignments", List.of(Map.of("name", "y", "value", "2"))));
        WorkflowGraph graph = graph(
                List.of(node("s", "START"), a, b, node("e", "END", Map.of("output", "汇合完成"))),
                List.of(edge("s", "a"), edge("s", "b"), edge("a", "e"), edge("b", "e")));

        engine.run(newRun(), graph, "x", validate(graph), this::sink);

        assertThat(nodeStartIds()).contains("a", "b");
        assertThat(countNodeStarts("e")).isEqualTo(1);
        assertThat(lastPayload("workflow_complete").get("output")).isEqualTo("汇合完成");
    }

    /** WORKFLOW 子流程：嵌套执行并把子输出带回父流程 */
    @Test
    void run_subWorkflow_nestsAndReturnsOutput() throws Exception {
        WorkflowGraph subGraph = graph(
                List.of(node("ss", "START"), node("se", "END", Map.of("output", "子输出：{{input}}"))),
                List.of(edge("ss", "se")));
        Workflow sub = new Workflow();
        sub.setId(99L);
        sub.setVersion(1);
        sub.setGraphJson(objectMapper.writeValueAsString(subGraph));
        when(workflowMapper.selectById(99L)).thenReturn(sub);

        GraphNode wf = node("w", "WORKFLOW", Map.of("workflowId", 99));
        WorkflowGraph graph = graph(
                List.of(node("s", "START"), wf, node("e", "END", Map.of("output", "父收到[{{w.text}}]"))),
                List.of(edge("s", "w"), edge("w", "e")));

        engine.run(newRun(), graph, "父输入", validate(graph), this::sink);

        Map<String, Object> wfOutput = nodeOutputPayload("w");
        assertThat(wfOutput.get("text")).isEqualTo("子输出：父输入");
        assertThat(wfOutput.get("subStatus")).isEqualTo("COMPLETED");
        assertThat(lastPayload("workflow_complete").get("output")).isEqualTo("父收到[子输出：父输入]");
    }

    // ==================== 失败处理 ====================

    /** 节点失败 + onError=CONTINUE：发 node_error 后继续执行到完成 */
    @Test
    void run_nodeErrorWithContinue_proceeds() {
        when(modelCacheManager.getChatModel(7L)).thenThrow(new RuntimeException("模型服务不可用"));
        GraphNode llm = node("l", "LLM", Map.of("modelId", 7, "prompt", "hi"));
        llm.setOnError("CONTINUE");
        WorkflowGraph graph = graph(
                List.of(node("s", "START"), llm, node("e", "END")),
                List.of(edge("s", "l"), edge("l", "e")));

        engine.run(newRun(), graph, "输入文本", validate(graph), this::sink);

        Map<String, Object> err = lastPayload("node_error");
        assertThat(err.get("nodeId")).isEqualTo("l");
        assertThat(err.get("error")).isEqualTo("模型服务不可用");
        assertThat(lastPayload("workflow_complete").get("output")).isEqualTo("输入文本");
    }

    /** 节点失败 + 默认 STOP：run 置 FAILED 并发 workflow_failed */
    @Test
    void run_nodeErrorDefaultStop_fails() {
        when(modelCacheManager.getChatModel(7L)).thenThrow(new RuntimeException("模型服务不可用"));
        GraphNode llm = node("l", "LLM", Map.of("modelId", 7, "prompt", "hi"));
        WorkflowGraph graph = graph(
                List.of(node("s", "START"), llm, node("e", "END")),
                List.of(edge("s", "l"), edge("l", "e")));

        engine.run(newRun(), graph, "输入文本", validate(graph), this::sink);

        assertThat(eventNames()).contains("node_error");
        assertThat(eventNames()).doesNotContain("workflow_complete");
        assertThat(lastPayload("workflow_failed").get("error")).asString().contains("模型服务不可用");
        assertThat(lastRunUpdate().getStatus()).isEqualTo("FAILED");
    }

    /** 未知节点类型：报"不支持的节点类型"（绕过校验器直造 validation） */
    @Test
    void run_unknownNodeType_fails() {
        GraphNode x = node("x", "UNKNOWN_X");
        WorkflowGraph graph = graph(
                List.of(node("s", "START"), x, node("e", "END")),
                List.of(edge("s", "x"), edge("x", "e")));
        Map<String, Integer> inDegree = new HashMap<>();
        inDegree.put("s", 0);
        inDegree.put("x", 1);
        inDegree.put("e", 1);
        GraphValidator.GraphValidationResult vr = new GraphValidator.GraphValidationResult(
                List.of(), Set.of(), inDegree, inDegree, graph);

        engine.run(newRun(), graph, "x", vr, this::sink);

        assertThat(lastPayload("node_error").get("error")).isEqualTo("不支持的节点类型：UNKNOWN_X");
        assertThat(eventNames()).contains("workflow_failed");
    }

    // ==================== 测试基建 ====================

    private void sink(String event, Map<String, Object> payload) {
        events.add(Map.entry(event, payload));
    }

    private List<String> eventNames() {
        return events.stream().map(Map.Entry::getKey).toList();
    }

    private Map<String, Object> lastPayload(String name) {
        return events.stream().filter(e -> name.equals(e.getKey()))
                .reduce((a, b) -> b).orElseThrow(() -> new AssertionError("缺少事件：" + name)).getValue();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> nodeOutputPayload(String nodeId) {
        return (Map<String, Object>) events.stream()
                .filter(e -> "node_output".equals(e.getKey()) && nodeId.equals(e.getValue().get("nodeId")))
                .reduce((a, b) -> b).orElseThrow(() -> new AssertionError("节点无输出事件：" + nodeId))
                .getValue().get("output");
    }

    private List<String> nodeStartIds() {
        return events.stream()
                .filter(e -> "node_start".equals(e.getKey()))
                .map(e -> String.valueOf(e.getValue().get("nodeId"))).toList();
    }

    private long countNodeStarts(String nodeId) {
        return nodeStartIds().stream().filter(nodeId::equals).count();
    }

    private WorkflowRun lastRunUpdate() {
        ArgumentCaptor<WorkflowRun> captor = ArgumentCaptor.forClass(WorkflowRun.class);
        verify(runMapper, atLeastOnce()).updateById(captor.capture());
        return captor.getValue();
    }

    private WorkflowRun newRun() {
        WorkflowRun run = new WorkflowRun();
        run.setId(1L);
        run.setWorkflowId(10L);
        run.setWorkflowVersion(1);
        run.setStatus("RUNNING");
        run.setExecutedNodes(0);
        run.setStartTime(LocalDateTime.now());
        return run;
    }

    private GraphValidator.GraphValidationResult validate(WorkflowGraph graph) {
        GraphValidator.GraphValidationResult vr = new GraphValidator().validate(graph);
        assertThat(vr.errors).as("测试图应通过校验").isEmpty();
        return vr;
    }

    private WorkflowGraph graph(List<GraphNode> nodes, List<GraphEdge> edges) {
        WorkflowGraph g = new WorkflowGraph();
        g.setNodes(nodes);
        g.setEdges(edges);
        return g;
    }

    private GraphNode node(String id, String type) {
        return node(id, type, new LinkedHashMap<>());
    }

    private GraphNode node(String id, String type, Map<String, Object> config) {
        GraphNode n = new GraphNode();
        n.setId(id);
        n.setType(type);
        n.setConfig(new LinkedHashMap<>(config));
        return n;
    }

    private GraphEdge edge(String source, String target) {
        return edge(source, target, null);
    }

    private GraphEdge edge(String source, String target, String sourceHandle) {
        GraphEdge e = new GraphEdge();
        e.setId(source + "->" + target + ":" + sourceHandle);
        e.setSource(source);
        e.setTarget(target);
        e.setSourceHandle(sourceHandle);
        return e;
    }
}
