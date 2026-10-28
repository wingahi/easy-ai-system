package com.wgh.rag.workflow.engine.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.workflow.dto.GraphEdge;
import com.wgh.rag.workflow.dto.GraphNode;
import com.wgh.rag.workflow.dto.NodeResult;
import com.wgh.rag.workflow.dto.RunContext;
import com.wgh.rag.workflow.dto.WorkflowGraph;
import com.wgh.rag.workflow.engine.VariableResolver;
import com.wgh.rag.workflow.engine.WorkflowEngine;
import com.wgh.rag.workflow.entity.Workflow;
import com.wgh.rag.workflow.entity.WorkflowRun;
import com.wgh.rag.workflow.mapper.WorkflowMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WORKFLOW 子流程节点执行器测试 —— 参数校验、子流程调用、输出模板、失败传播。
 */
@ExtendWith(MockitoExtension.class)
class WorkflowNodeExecutorTest {

    @Mock
    private WorkflowMapper workflowMapper;
    @Mock
    private WorkflowEngine workflowEngine;
    @Mock
    private ObjectProvider<WorkflowEngine> engineProvider;

    private final VariableResolver variableResolver = new VariableResolver();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private WorkflowNodeExecutor executor() {
        return new WorkflowNodeExecutor(variableResolver, workflowMapper, objectMapper, engineProvider);
    }

    @Test
    void execute_missingWorkflowId_throws() {
        GraphNode node = node(Map.of());

        assertThatThrownBy(() -> executor().execute(context(node, new RunContext())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("子流程节点缺少 workflowId");
    }

    @Test
    void execute_subWorkflowNotFound_throws() {
        when(workflowMapper.selectById(99L)).thenReturn(null);
        GraphNode node = node(Map.of("workflowId", 99));

        assertThatThrownBy(() -> executor().execute(context(node, new RunContext())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("子流程不存在：99");
    }

    @Test
    void execute_invalidSubGraph_throwsValidationError() throws Exception {
        Workflow sub = subWorkflow(twoStartGraphJson());
        when(workflowMapper.selectById(99L)).thenReturn(sub);
        GraphNode node = node(Map.of("workflowId", 99));

        assertThatThrownBy(() -> executor().execute(context(node, new RunContext())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("子流程图校验失败：");
    }

    @Test
    void execute_blankTemplates_passesLastOutputAndReturnsSubOutput() throws Exception {
        when(workflowMapper.selectById(99L)).thenReturn(subWorkflow(validSubGraphJson()));
        when(engineProvider.getObject()).thenReturn(workflowEngine);
        WorkflowRun subRun = new WorkflowRun();
        subRun.setId(500L);
        subRun.setStatus("COMPLETED");
        subRun.setOutputText("子输出");
        when(workflowEngine.runSubWorkflow(any(), any(), any(), any(), anyInt())).thenReturn(subRun);

        RunContext ctx = new RunContext();
        ctx.getVars().put("lastOutput", "父上游");
        GraphNode node = node(Map.of("workflowId", 99));

        NodeResult result = executor().execute(context(node, ctx));

        verify(workflowEngine).runSubWorkflow(any(), any(), eq("父上游"), any(), eq(2));
        assertThat(result.getOutput())
                .containsEntry("text", "子输出")
                .containsEntry("subRunId", 500L)
                .containsEntry("subStatus", "COMPLETED");
    }

    @Test
    void execute_outputTemplate_rendersViaTempVar() throws Exception {
        when(workflowMapper.selectById(99L)).thenReturn(subWorkflow(validSubGraphJson()));
        when(engineProvider.getObject()).thenReturn(workflowEngine);
        WorkflowRun subRun = new WorkflowRun();
        subRun.setId(500L);
        subRun.setStatus("COMPLETED");
        subRun.setOutputText("子输出");
        when(workflowEngine.runSubWorkflow(any(), any(), any(), any(), anyInt())).thenReturn(subRun);

        RunContext ctx = new RunContext();
        ctx.getVars().put("input", "父输入");
        GraphNode node = node(Map.of("workflowId", 99,
                "inputTemplate", "加工{{input}}", "outputTemplate", "包装[{{vars._subOutput_99}}]"));

        NodeResult result = executor().execute(context(node, ctx));

        verify(workflowEngine).runSubWorkflow(any(), any(), eq("加工父输入"), any(), eq(2));
        assertThat(ctx.getVars()).containsEntry("_subOutput_99", "子输出");
        assertThat(result.getOutput()).containsEntry("text", "包装[子输出]");
    }

    @Test
    void execute_failedSubRun_throws() throws Exception {
        when(workflowMapper.selectById(99L)).thenReturn(subWorkflow(validSubGraphJson()));
        when(engineProvider.getObject()).thenReturn(workflowEngine);
        WorkflowRun subRun = new WorkflowRun();
        subRun.setStatus("FAILED");
        subRun.setErrorMessage("boom");
        when(workflowEngine.runSubWorkflow(any(), any(), any(), any(), anyInt())).thenReturn(subRun);
        GraphNode node = node(Map.of("workflowId", 99));

        assertThatThrownBy(() -> executor().execute(context(node, new RunContext())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("子流程执行失败：boom");
    }

    private String validSubGraphJson() throws Exception {
        return objectMapper.writeValueAsString(subGraph(
                List.of(subNode("ss", "START"), subNode("se", "END")),
                List.of(edge("ss", "se"))));
    }

    private String twoStartGraphJson() throws Exception {
        return objectMapper.writeValueAsString(subGraph(
                List.of(subNode("s1", "START"), subNode("s2", "START"), subNode("se", "END")),
                List.of(edge("s1", "se"))));
    }

    private Workflow subWorkflow(String graphJson) {
        Workflow sub = new Workflow();
        sub.setId(99L);
        sub.setVersion(1);
        sub.setGraphJson(graphJson);
        return sub;
    }

    private WorkflowGraph subGraph(List<GraphNode> nodes, List<GraphEdge> edges) {
        WorkflowGraph g = new WorkflowGraph();
        g.setNodes(nodes);
        g.setEdges(edges);
        return g;
    }

    private GraphNode subNode(String id, String type) {
        GraphNode n = new GraphNode();
        n.setId(id);
        n.setType(type);
        n.setConfig(new LinkedHashMap<>());
        return n;
    }

    private GraphEdge edge(String source, String target) {
        GraphEdge e = new GraphEdge();
        e.setId(source + "->" + target);
        e.setSource(source);
        e.setTarget(target);
        return e;
    }

    private NodeExecutionContext context(GraphNode node, RunContext ctx) {
        return new NodeExecutionContext(node, ctx, new WorkflowRun(), 1);
    }

    private GraphNode node(Map<String, Object> config) {
        GraphNode n = new GraphNode();
        n.setId("w");
        n.setType("WORKFLOW");
        n.setConfig(new LinkedHashMap<>(config));
        return n;
    }
}
