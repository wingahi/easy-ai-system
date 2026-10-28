package com.wgh.rag.workflow.engine.executor;

import com.wgh.rag.workflow.dto.GraphNode;
import com.wgh.rag.workflow.dto.NodeResult;
import com.wgh.rag.workflow.dto.RunContext;
import com.wgh.rag.workflow.engine.ToolNodeInvoker;
import com.wgh.rag.workflow.engine.VariableResolver;
import com.wgh.rag.workflow.entity.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TOOL 节点执行器测试 —— toolId 校验、参数渲染、success 标记。
 */
@ExtendWith(MockitoExtension.class)
class ToolNodeExecutorTest {

    @Mock
    private ToolNodeInvoker toolNodeInvoker;

    private final VariableResolver variableResolver = new VariableResolver();

    private ToolNodeExecutor executor() {
        return new ToolNodeExecutor(variableResolver, toolNodeInvoker);
    }

    @Test
    void execute_missingToolId_throws() {
        GraphNode node = node(Map.of());

        assertThatThrownBy(() -> executor().execute(context(node, new RunContext())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("工具节点缺少 toolId");
    }

    @Test
    void execute_rendersStringArgsKeepsOthers() throws Exception {
        when(toolNodeInvoker.invoke(eq(3L), any())).thenReturn("ok");
        RunContext ctx = new RunContext();
        ctx.getVars().put("input", "北京");
        GraphNode node = node(Map.of("toolId", 3,
                "arguments", Map.of("city", "{{input}}", "days", 3)));

        NodeResult result = executor().execute(context(node, ctx));

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(toolNodeInvoker).invoke(eq(3L), captor.capture());
        assertThat(captor.getValue()).containsEntry("city", "北京").containsEntry("days", 3);
        assertThat(result.getOutput()).containsEntry("text", "ok").containsEntry("success", true);
    }

    @Test
    void execute_errorText_marksUnsuccessful() throws Exception {
        when(toolNodeInvoker.invoke(eq(3L), any())).thenReturn("Error: 超时");
        GraphNode node = node(Map.of("toolId", 3));

        NodeResult result = executor().execute(context(node, new RunContext()));

        assertThat(result.getOutput()).containsEntry("success", false);
    }

    private NodeExecutionContext context(GraphNode node, RunContext ctx) {
        return new NodeExecutionContext(node, ctx, new WorkflowRun(), 1);
    }

    private GraphNode node(Map<String, Object> config) {
        GraphNode n = new GraphNode();
        n.setId("t");
        n.setType("TOOL");
        n.setConfig(new LinkedHashMap<>(config));
        return n;
    }
}
