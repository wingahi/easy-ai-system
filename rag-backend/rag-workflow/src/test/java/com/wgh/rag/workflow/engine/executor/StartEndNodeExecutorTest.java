package com.wgh.rag.workflow.engine.executor;

import com.wgh.rag.workflow.dto.GraphNode;
import com.wgh.rag.workflow.dto.NodeResult;
import com.wgh.rag.workflow.dto.RunContext;
import com.wgh.rag.workflow.engine.VariableResolver;
import com.wgh.rag.workflow.entity.WorkflowRun;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * START / END 节点执行器测试。
 */
class StartEndNodeExecutorTest {

    private final VariableResolver variableResolver = new VariableResolver();
    private final StartNodeExecutor startExecutor = new StartNodeExecutor();
    private final EndNodeExecutor endExecutor = new EndNodeExecutor(variableResolver);

    @Test
    void start_outputsInputAsText() {
        RunContext ctx = new RunContext();
        ctx.getVars().put("input", "你好");

        NodeResult result = startExecutor.execute(context(node("s", "START"), ctx));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).containsEntry("text", "你好");
    }

    @Test
    void start_missingInput_outputsEmpty() {
        NodeResult result = startExecutor.execute(context(node("s", "START"), new RunContext()));

        assertThat(result.getOutput()).containsEntry("text", "");
    }

    @Test
    void end_blankTemplate_usesLastOutput() {
        RunContext ctx = new RunContext();
        ctx.getVars().put("lastOutput", "上游结果");

        NodeResult result = endExecutor.execute(context(node("e", "END"), ctx));

        assertThat(result.getOutput()).containsEntry("text", "上游结果");
    }

    @Test
    void end_template_rendersVariables() {
        RunContext ctx = new RunContext();
        ctx.getVars().put("input", "世界");
        GraphNode end = node("e", "END", Map.of("output", "你好，{{input}}！"));

        NodeResult result = endExecutor.execute(context(end, ctx));

        assertThat(result.getOutput()).containsEntry("text", "你好，世界！");
    }

    @Test
    void end_noOutputAndNoLastOutput_emptyText() {
        NodeResult result = endExecutor.execute(context(node("e", "END"), new RunContext()));

        assertThat(result.getOutput()).containsEntry("text", "");
    }

    private NodeExecutionContext context(GraphNode node, RunContext ctx) {
        return new NodeExecutionContext(node, ctx, new WorkflowRun(), 1);
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
}
