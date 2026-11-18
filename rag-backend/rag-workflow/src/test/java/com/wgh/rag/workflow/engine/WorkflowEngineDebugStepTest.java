package com.wgh.rag.workflow.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.common.metrics.RagMetrics;
import com.wgh.rag.workflow.constant.WorkflowConstants;
import com.wgh.rag.workflow.dto.DebugStepResult;
import com.wgh.rag.workflow.dto.GraphEdge;
import com.wgh.rag.workflow.dto.GraphNode;
import com.wgh.rag.workflow.dto.RunContext;
import com.wgh.rag.workflow.dto.WorkflowGraph;
import com.wgh.rag.workflow.entity.WorkflowNodeRun;
import com.wgh.rag.workflow.entity.WorkflowRun;
import com.wgh.rag.workflow.engine.executor.ConditionNodeExecutor;
import com.wgh.rag.workflow.engine.executor.EndNodeExecutor;
import com.wgh.rag.workflow.engine.executor.ExpressionNodeExecutor;
import com.wgh.rag.workflow.engine.executor.LoopNodeExecutor;
import com.wgh.rag.workflow.engine.executor.NodeExecutorRegistry;
import com.wgh.rag.workflow.engine.executor.StartNodeExecutor;
import com.wgh.rag.workflow.engine.executor.VariableNodeExecutor;
import com.wgh.rag.workflow.mapper.WorkflowNodeRunMapper;
import com.wgh.rag.workflow.mapper.WorkflowRunMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

/**
 * 单步调试执行：逐节点推进、输入/输出快照、指定节点停止、失败终止。
 * 普通运行链路（{@link WorkflowEngine#run}）不被调试逻辑影响，由 WorkflowEngineTest 覆盖。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("工作流单步调试")
class WorkflowEngineDebugStepTest {

    @Mock
    private WorkflowRunMapper runMapper;
    @Mock
    private WorkflowNodeRunMapper nodeRunMapper;

    private final VariableResolver variableResolver = new VariableResolver();
    private final ConditionEvaluator conditionEvaluator = new ConditionEvaluator();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private WorkflowEngine engine;

    @BeforeEach
    void setUp() {
        NodeExecutorRegistry registry = new NodeExecutorRegistry(List.of(
                new StartNodeExecutor(),
                new VariableNodeExecutor(variableResolver),
                new ExpressionNodeExecutor(objectMapper),
                new ConditionNodeExecutor(variableResolver, conditionEvaluator),
                new LoopNodeExecutor(),
                new EndNodeExecutor(variableResolver)));
        engine = new WorkflowEngine(runMapper, nodeRunMapper, variableResolver, conditionEvaluator,
                registry, objectMapper, org.mockito.Mockito.mock(RagMetrics.class),
                new com.wgh.rag.workflow.config.WorkflowContextProperties());
        lenient().when(runMapper.updateById(any(WorkflowRun.class))).thenReturn(1);
        lenient().when(nodeRunMapper.insert(any(WorkflowNodeRun.class))).thenReturn(1);
        lenient().when(nodeRunMapper.updateById(any(WorkflowNodeRun.class))).thenReturn(1);
    }

    /** 每步只执行一个节点：START → VARIABLE → EXPRESSION → END，逐步返回输入/输出快照 */
    @Test
    void stepByStep_executesOneNodeAndReturnsSnapshots() {
        WorkflowGraph graph = graph(
                List.of(node("s", "START"),
                        node("v", "VARIABLE", Map.of("assignments",
                                List.of(Map.of("name", "base", "value", "3")))),
                        node("e", "EXPRESSION", Map.of(
                                "expression", "#fn.intOf(#vars.base) + 1", "resultVar", "total")),
                        node("end", "END", Map.of("output", "计算：{{vars.total}}/{{e.text}}"))),
                List.of(edge("s", "v"), edge("v", "e"), edge("e", "end")));

        WorkflowRun run = newRun();
        RunContext ctx = newContext();

        DebugStepResult first = step(run, graph, ctx, 1, null);
        assertThat(first.getStatus()).isEqualTo(WorkflowConstants.STATUS_DEBUG_PAUSED);
        assertThat(first.getSteps()).extracting(DebugStepResult.Step::getNodeId).containsExactly("s");
        assertThat(first.getSteps().get(0).getInput()).containsKey("config").containsKey("upstream");
        assertThat(first.getRunId()).isEqualTo(run.getId());

        DebugStepResult second = step(run, graph, ctx, 1, null);
        assertThat(second.getSteps()).extracting(DebugStepResult.Step::getNodeId).containsExactly("v");

        DebugStepResult third = step(run, graph, ctx, 1, null);
        assertThat(third.getSteps()).extracting(DebugStepResult.Step::getNodeId).containsExactly("e");
        assertThat(third.getSteps().get(0).getOutput())
                .containsEntry("value", 4)
                .containsEntry("text", "4");
        assertThat(third.getSteps().get(0).getInput().get("config")).isInstanceOf(Map.class);
        assertThat(asMap(third.getSteps().get(0).getInput().get("config")))
                .containsEntry("expression", "#fn.intOf(#vars.base) + 1");

        DebugStepResult last = step(run, graph, ctx, 1, null);
        assertThat(last.getStatus()).isEqualTo(WorkflowConstants.STATUS_COMPLETED);
        assertThat(last.isFinished()).isTrue();
        assertThat(last.getOutput()).isEqualTo("计算：4/4");
        // END 输入快照：配置已渲染 + 直接上游输出
        DebugStepResult.Step endStep = last.getSteps().get(0);
        assertThat(asMap(endStep.getInput().get("config")))
                .containsEntry("output", "计算：4/4");
        assertThat(asMap(endStep.getInput().get("upstream")))
                .containsKey("e");
    }

    /** stopAtNodeId：执行到指定节点后暂停，剩余节点留在游标中 */
    @Test
    void stopAtNodeId_pausesAtTarget() {
        WorkflowGraph graph = graph(
                List.of(node("s", "START"),
                        node("v", "VARIABLE", Map.of("assignments",
                                List.of(Map.of("name", "base", "value", "3")))),
                        node("e", "EXPRESSION", Map.of("expression", "#fn.intOf(#vars.base) + 1")),
                        node("end", "END", Map.of("output", "done"))),
                List.of(edge("s", "v"), edge("v", "e"), edge("e", "end")));

        WorkflowRun run = newRun();
        RunContext ctx = newContext();

        DebugStepResult result = step(run, graph, ctx, 10, "e");

        assertThat(result.getStatus()).isEqualTo(WorkflowConstants.STATUS_DEBUG_PAUSED);
        assertThat(result.getSteps()).extracting(DebugStepResult.Step::getNodeId).containsExactly("s", "v", "e");
        assertThat(ctx.getDebugFrontier()).containsExactly("end");
    }

    /** 节点执行失败：步进终止并返回 FAILED 与错误信息 */
    @Test
    void nodeFailure_stopsWithFailed() {
        WorkflowGraph graph = graph(
                List.of(node("s", "START"),
                        node("e", "EXPRESSION", Map.of("expression", "#vars.missing.value")),
                        node("end", "END", Map.of("output", "done"))),
                List.of(edge("s", "e"), edge("e", "end")));

        WorkflowRun run = newRun();

        DebugStepResult result = step(run, graph, newContext(), 5, null);

        assertThat(result.getStatus()).isEqualTo(WorkflowConstants.STATUS_FAILED);
        assertThat(result.getError()).contains("表达式求值失败");
        assertThat(result.getSteps()).extracting(DebugStepResult.Step::getStatus)
                .contains(WorkflowConstants.STATUS_FAILED);
    }

    // ==================== 辅助 ====================

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    private DebugStepResult step(WorkflowRun run, WorkflowGraph graph, RunContext ctx,
                                 int maxSteps, String stopAtNodeId) {
        GraphValidator.GraphValidationResult vr = new GraphValidator().validate(graph);
        assertThat(vr.errors).as("测试图应通过校验").isEmpty();
        return engine.debugStep(run, graph, vr, ctx, maxSteps, stopAtNodeId);
    }

    private WorkflowRun newRun() {
        WorkflowRun run = new WorkflowRun();
        run.setId(501L);
        run.setWorkflowId(9L);
        run.setWorkflowVersion(1);
        run.setInputText("原始输入");
        run.setStatus(WorkflowConstants.STATUS_RUNNING);
        run.setExecutedNodes(0);
        run.setStartTime(LocalDateTime.now());
        return run;
    }

    private RunContext newContext() {
        RunContext ctx = new RunContext();
        ctx.getVars().put("input", "原始输入");
        ctx.getVars().put("lastOutput", "");
        return ctx;
    }

    private WorkflowGraph graph(List<GraphNode> nodes, List<GraphEdge> edges) {
        WorkflowGraph g = new WorkflowGraph();
        g.setNodes(new ArrayList<>(nodes));
        g.setEdges(new ArrayList<>(edges));
        return g;
    }

    private GraphNode node(String id, String type) {
        return node(id, type, Map.of());
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
        e.setSource(source);
        e.setTarget(target);
        e.setSourceHandle("default");
        return e;
    }
}
