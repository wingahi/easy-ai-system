package com.wgh.rag.workflow.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.common.metrics.RagMetrics;
import com.wgh.rag.workflow.config.WorkflowContextProperties;
import com.wgh.rag.workflow.dto.GraphEdge;
import com.wgh.rag.workflow.dto.GraphNode;
import com.wgh.rag.workflow.dto.RunContext;
import com.wgh.rag.workflow.dto.WorkflowGraph;
import com.wgh.rag.workflow.engine.executor.EndNodeExecutor;
import com.wgh.rag.workflow.engine.executor.ExpressionNodeExecutor;
import com.wgh.rag.workflow.engine.executor.NodeExecutorRegistry;
import com.wgh.rag.workflow.engine.executor.StartNodeExecutor;
import com.wgh.rag.workflow.engine.executor.VariableNodeExecutor;
import com.wgh.rag.workflow.entity.WorkflowNodeRun;
import com.wgh.rag.workflow.entity.WorkflowRun;
import com.wgh.rag.workflow.mapper.WorkflowNodeRunMapper;
import com.wgh.rag.workflow.mapper.WorkflowRunMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

/**
 * 工作流节点输出上限（output_json）与运行上下文上限（context_json）落库策略，
 * 以及 lastOutput「最近一个有意义的文本输出」语义。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("工作流上下文落库上限")
class WorkflowContextBudgetTest {

    @Mock
    private WorkflowRunMapper runMapper;
    @Mock
    private WorkflowNodeRunMapper nodeRunMapper;

    private final VariableResolver variableResolver = new VariableResolver();
    private final ConditionEvaluator conditionEvaluator = new ConditionEvaluator();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private WorkflowContextProperties properties;
    private WorkflowEngine engine;
    private ConcurrentLinkedQueue<Map.Entry<String, Map<String, Object>>> events;

    @BeforeEach
    void setUp() {
        properties = new WorkflowContextProperties();
        NodeExecutorRegistry registry = new NodeExecutorRegistry(List.of(
                new StartNodeExecutor(),
                new VariableNodeExecutor(variableResolver),
                new ExpressionNodeExecutor(objectMapper),
                new EndNodeExecutor(variableResolver)));
        engine = new WorkflowEngine(runMapper, nodeRunMapper, variableResolver, conditionEvaluator,
                registry, objectMapper, org.mockito.Mockito.mock(RagMetrics.class), properties,
                org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class));
        events = new ConcurrentLinkedQueue<>();
        lenient().when(runMapper.updateById(any(WorkflowRun.class))).thenReturn(1);
        lenient().when(runMapper.selectById(any())).thenAnswer(inv -> {
            WorkflowRun r = new WorkflowRun();
            r.setId(inv.getArgument(0));
            r.setStatus("RUNNING");
            return r;
        });
        lenient().when(nodeRunMapper.insert(any(WorkflowNodeRun.class))).thenReturn(1);
        lenient().when(nodeRunMapper.updateById(any(WorkflowNodeRun.class))).thenReturn(1);
    }

    /** 单节点输出超过上限：output_json 只落 text 预览 + truncated / originalBytes 标记 */
    @Test
    void nodeOutput_overLimit_persistsSummaryWithTruncationMarker() throws Exception {
        properties.setNodeOutputMaxBytes(500);
        String bigText = "A".repeat(3000);
        WorkflowGraph graph = graph(
                List.of(node("s", "START"),
                        node("v", "VARIABLE", Map.of("assignments",
                                List.of(Map.of("name", "text", "value", bigText)))),
                        node("e", "END")),
                List.of(edge("s", "v"), edge("v", "e")));

        engine.run(newRun(), graph, "输入", validate(graph), this::sink);

        WorkflowNodeRun stored = lastNodeRun("v");
        assertThat(stored.getOutputJson().getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(properties.getNodeOutputMaxBytes());
        Map<String, Object> persisted = objectMapper.readValue(stored.getOutputJson(),
                new TypeReference<LinkedHashMap<String, Object>>() {});
        assertThat(persisted.get("truncated")).isEqualTo(true);
        assertThat(((Number) persisted.get("originalBytes")).intValue()).isGreaterThan(3000);
        assertThat(String.valueOf(persisted.get("text"))).hasSizeLessThan(bigText.length());
    }

    /** 上下文总大小超过上限：按节点插入顺序早期优先摘要，整体仍超限则剔除最早的节点输出 */
    @Test
    void contextJson_overLimit_trimsEarlierNodeOutputsFirst() throws Exception {
        properties.setContextJsonMaxBytes(600);
        properties.setNodeOutputMaxBytes(400);
        RunContext ctx = new RunContext();
        ctx.getVars().put("input", "x");
        List<String> nodeIds = List.of("n1", "n2", "n3", "n4", "n5", "n6");
        for (String nodeId : nodeIds) {
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("text", "B".repeat(800));
            ctx.getNodeOutputs().put(nodeId, output);
        }

        String json = engine.serializeContext(ctx);

        assertThat(json.getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(properties.getContextJsonMaxBytes());
        // 序列化不修改内存上下文
        assertThat(ctx.getNodeOutputs()).hasSize(nodeIds.size());

        Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {});
        @SuppressWarnings("unchecked")
        Map<String, Object> persistedOutputs = (Map<String, Object>) parsed.get("nodeOutputs");
        List<String> kept = new ArrayList<>(persistedOutputs.keySet());
        // 被摘要/剔除的必须是早期节点：保留下来的键是原始顺序的后缀
        assertThat(kept).isEqualTo(nodeIds.subList(nodeIds.size() - kept.size(), nodeIds.size()));
        assertThat(kept.size()).isLessThan(nodeIds.size());
    }

    /** 未超过上限：上下文原样落库，不出现截断标记 */
    @Test
    void contextJson_underLimit_keepsFullOutput() throws Exception {
        RunContext ctx = new RunContext();
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("text", "完整输出");
        ctx.getNodeOutputs().put("n1", output);

        String json = engine.serializeContext(ctx);

        assertThat(json).contains("完整输出").doesNotContain("\"truncated\":true");
    }

    /** lastOutput 只接受有意义的文本：表达式节点产出的大 JSON 不覆盖上一段文本输出 */
    @Test
    void lastOutput_ignoresJsonOutput() {
        WorkflowGraph graph = graph(
                List.of(node("s", "START"),
                        node("x", "EXPRESSION", Map.of("expression", "#vars")),
                        node("e", "END", Map.of("output", "{{last}}"))),
                List.of(edge("s", "x"), edge("x", "e")));

        engine.run(newRun(), graph, "输入", validate(graph), this::sink);

        @SuppressWarnings("unchecked")
        Map<String, Object> expressionOutput = (Map<String, Object>) lastPayload("node_output", "x").get("output");
        assertThat(String.valueOf(expressionOutput.get("text"))).startsWith("{");
        assertThat(lastPayload("workflow_complete", null).get("output")).isEqualTo("输入");
    }

    // ==================== 测试基建 ====================

    private void sink(String event, Map<String, Object> payload) {
        events.add(Map.entry(event, payload));
    }

    private Map<String, Object> lastPayload(String name, String nodeId) {
        return events.stream()
                .filter(e -> name.equals(e.getKey()))
                .filter(e -> nodeId == null || nodeId.equals(e.getValue().get("nodeId")))
                .reduce((a, b) -> b)
                .orElseThrow(() -> new AssertionError("缺少事件：" + name)).getValue();
    }

    private WorkflowNodeRun lastNodeRun(String nodeId) {
        ArgumentCaptor<WorkflowNodeRun> captor = ArgumentCaptor.forClass(WorkflowNodeRun.class);
        verify(nodeRunMapper, atLeastOnce()).updateById(captor.capture());
        return captor.getAllValues().stream()
                .filter(nr -> nodeId.equals(nr.getNodeId()))
                .reduce((a, b) -> b)
                .orElseThrow(() -> new AssertionError("缺少节点运行记录：" + nodeId));
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
        GraphEdge e = new GraphEdge();
        e.setId(source + "->" + target);
        e.setSource(source);
        e.setTarget(target);
        return e;
    }
}
