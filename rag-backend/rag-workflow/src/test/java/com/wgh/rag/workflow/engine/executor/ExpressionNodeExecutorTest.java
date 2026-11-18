package com.wgh.rag.workflow.engine.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.workflow.constant.WorkflowConstants;
import com.wgh.rag.workflow.dto.GraphNode;
import com.wgh.rag.workflow.dto.NodeResult;
import com.wgh.rag.workflow.dto.RunContext;
import com.wgh.rag.workflow.entity.WorkflowRun;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EXPRESSION 节点：求值、输出结构、resultVar 写入变量池、非法表达式失败。
 */
@DisplayName("EXPRESSION 节点")
class ExpressionNodeExecutorTest {

    private final ExpressionNodeExecutor executor = new ExpressionNodeExecutor(new ObjectMapper());

    private NodeExecutionContext context(GraphNode node, RunContext ctx) {
        return new NodeExecutionContext(node, ctx, new WorkflowRun(), 1);
    }

    private GraphNode node(String id, Map<String, Object> config) {
        GraphNode n = new GraphNode();
        n.setId(id);
        n.setType(WorkflowConstants.NODE_EXPRESSION);
        n.setConfig(new LinkedHashMap<>(config));
        return n;
    }

    @Test
    @DisplayName("对上游输出与变量求值，输出 value/text/resultType")
    void evaluatesUpstreamAndVars() {
        RunContext ctx = new RunContext();
        ctx.getNodeOutputs().put("n_llm_1", Map.of("text", "abc"));
        ctx.getVars().put("count", 2);

        NodeResult result = executor.execute(context(node("e1", Map.of(
                "expression", "#fn.upper(#nodes.n_llm_1.text) + '-' + #vars.count")), ctx));

        assertThat(result.getOutput())
                .containsEntry("text", "ABC-2")
                .containsEntry("value", "ABC-2")
                .containsEntry("resultType", "String");
    }

    @Test
    @DisplayName("resultVar 将结果写入变量池，供下游 {{vars.x}} 引用")
    void resultVarWritesToVars() {
        RunContext ctx = new RunContext();
        ctx.getNodeOutputs().put("n_expr_0", Map.of("text", "4"));

        executor.execute(context(node("e2", Map.of(
                "expression", "#fn.intOf(#nodes.n_expr_0.text) + 1",
                "resultVar", "total")), ctx));

        assertThat(ctx.getVars()).containsEntry("total", 5);
    }

    @Test
    @DisplayName("Map/List 结果以 JSON 字符串输出")
    void mapResultStringifiedAsJson() {
        RunContext ctx = new RunContext();

        NodeResult result = executor.execute(context(node("e3", Map.of(
                "expression", "#fn.split('a,b,c', ',')")), ctx));

        assertThat(result.getOutput().get("text")).isEqualTo("[\"a\",\"b\",\"c\"]");
        assertThat(result.getOutput().get("resultType")).isEqualTo("ArrayList");
    }

    @Test
    @DisplayName("缺失表达式 / 危险表达式 → 节点执行失败")
    void invalidExpressionFails() {
        assertThatThrownBy(() -> executor.execute(context(node("e4", Map.of()), new RunContext())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("缺少表达式");

        assertThatThrownBy(() -> executor.execute(context(node("e5",
                Map.of("expression", "T(java.lang.Runtime).getRuntime()")), new RunContext())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("类型引用");
    }

    @Test
    @DisplayName("表达式读取 loop 轮次")
    void readsLoopIteration() {
        RunContext ctx = new RunContext();
        ctx.setCurrentLoopIteration(3);

        NodeResult result = executor.execute(context(node("e6", Map.of("expression", "#loop * 2")), ctx));

        assertThat(result.getOutput()).containsEntry("value", 6);
    }

    @Test
    @DisplayName("空结果输出空字符串")
    void nullResultBecomesEmptyText() {
        RunContext ctx = new RunContext();

        NodeResult result = executor.execute(context(node("e7", Map.of("expression", "#fn.orElse(null, null)")), ctx));

        assertThat(result.getOutput()).containsEntry("text", "").containsEntry("resultType", "null");
    }
}
