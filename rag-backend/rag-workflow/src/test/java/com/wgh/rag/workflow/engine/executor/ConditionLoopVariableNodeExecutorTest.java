package com.wgh.rag.workflow.engine.executor;

import com.wgh.rag.workflow.dto.GraphNode;
import com.wgh.rag.workflow.dto.NodeResult;
import com.wgh.rag.workflow.dto.RunContext;
import com.wgh.rag.workflow.engine.ConditionEvaluator;
import com.wgh.rag.workflow.engine.VariableResolver;
import com.wgh.rag.workflow.entity.WorkflowRun;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CONDITION / LOOP / VARIABLE 节点执行器测试（纯逻辑，真实 VariableResolver + ConditionEvaluator）。
 */
class ConditionLoopVariableNodeExecutorTest {

    private final VariableResolver variableResolver = new VariableResolver();
    private final ConditionNodeExecutor conditionExecutor =
            new ConditionNodeExecutor(variableResolver, new ConditionEvaluator());
    private final LoopNodeExecutor loopExecutor = new LoopNodeExecutor();
    private final VariableNodeExecutor variableExecutor = new VariableNodeExecutor(variableResolver);

    // ---------- CONDITION ----------

    /** CONDITION 先渲染表达式再求值，条件成立时 result 为 true 并回填渲染后的表达式 */
    @Test
    void condition_rendersThenEvaluates_true() {
        RunContext ctx = new RunContext();
        ctx.getVars().put("input", "ok");
        GraphNode node = node("c", "CONDITION", Map.of("expression", "{{input}} == \"ok\""));

        NodeResult result = conditionExecutor.execute(context(node, ctx));

        assertThat(result.getOutput())
                .containsEntry("result", true)
                .containsEntry("expression", "ok == \"ok\"");
    }

    /** 表达式不成立时 CONDITION 输出 result 为 false */
    @Test
    void condition_notMatching_false() {
        RunContext ctx = new RunContext();
        ctx.getVars().put("input", "no");
        GraphNode node = node("c", "CONDITION", Map.of("expression", "{{input}} == \"ok\""));

        NodeResult result = conditionExecutor.execute(context(node, ctx));

        assertThat(result.getOutput()).containsEntry("result", false);
    }

    // ---------- LOOP ----------

    /** LOOP 首次进入时把迭代计数初始化为 1 并写入上下文 */
    @Test
    void loop_firstEntry_initializesIteration() {
        RunContext ctx = new RunContext();
        GraphNode node = node("lp", "LOOP", Map.of("maxIterations", 5));

        NodeResult result = loopExecutor.execute(context(node, ctx));

        assertThat(ctx.getLoopIterations()).containsEntry("lp", 1);
        assertThat(ctx.getCurrentLoopIteration()).isEqualTo(1);
        assertThat(result.getOutput())
                .containsEntry("iteration", 1)
                .containsEntry("maxIterations", 5);
    }

    /** LOOP 未配置 maxIterations 时默认取 3 */
    @Test
    void loop_defaultMaxIterations_is3() {
        RunContext ctx = new RunContext();

        NodeResult result = loopExecutor.execute(context(node("lp", "LOOP", Map.of()), ctx));

        assertThat(result.getOutput()).containsEntry("maxIterations", 3);
    }

    // ---------- VARIABLE ----------

    /** VARIABLE 渲染赋值表达式后同时写入 vars 与节点输出（非字符串值被转成字符串） */
    @Test
    void variable_assignmentsRenderedAndWrittenToVars() {
        RunContext ctx = new RunContext();
        ctx.getVars().put("input", "原始");
        GraphNode node = node("v", "VARIABLE", Map.of("assignments", List.of(
                Map.of("name", "x", "value", "{{input}}-后缀"),
                Map.of("name", "n", "value", 42))));

        NodeResult result = variableExecutor.execute(context(node, ctx));

        assertThat(ctx.getVars()).containsEntry("x", "原始-后缀").containsEntry("n", "42");
        assertThat(result.getOutput()).containsEntry("x", "原始-后缀").containsEntry("n", "42");
    }

    /** assignments 不是列表时 VARIABLE 节点输出为空 Map */
    @Test
    void variable_nonListAssignments_emptyOutput() {
        GraphNode node = node("v", "VARIABLE", Map.of("assignments", "not-a-list"));

        NodeResult result = variableExecutor.execute(context(node, new RunContext()));

        assertThat(result.getOutput()).isEmpty();
    }

    /** VARIABLE 赋值可引用上游节点输出的路径变量 */
    @Test
    void variable_referencesNodeOutput() {
        RunContext ctx = new RunContext();
        ctx.getNodeOutputs().put("up", Map.of("text", "上游文本"));
        GraphNode node = node("v", "VARIABLE", Map.of("assignments",
                List.of(Map.of("name", "copy", "value", "{{up.text}}"))));

        NodeResult result = variableExecutor.execute(context(node, ctx));

        assertThat(result.getOutput()).containsEntry("copy", "上游文本");
    }

    private NodeExecutionContext context(GraphNode node, RunContext ctx) {
        return new NodeExecutionContext(node, ctx, new WorkflowRun(), 1);
    }

    private GraphNode node(String id, String type, Map<String, Object> config) {
        GraphNode n = new GraphNode();
        n.setId(id);
        n.setType(type);
        n.setConfig(new LinkedHashMap<>(config));
        return n;
    }
}
