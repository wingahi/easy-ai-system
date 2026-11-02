package com.wgh.rag.workflow.engine.executor;

import com.wgh.rag.agent.StepExecutor;
import com.wgh.rag.agent.dto.AgentStepResult;
import com.wgh.rag.agent.dto.PlanDTO;
import com.wgh.rag.agent.planner.Planner;
import com.wgh.rag.workflow.dto.GraphNode;
import com.wgh.rag.workflow.dto.NodeResult;
import com.wgh.rag.workflow.dto.RunContext;
import com.wgh.rag.workflow.engine.VariableResolver;
import com.wgh.rag.workflow.entity.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.when;

/**
 * AgentNodeExecutor 单元测试 —— 节点类型注册、goal 校验、Planner+StepExecutor 链路、异常。
 * <p>
 * 注意：Long/List/Map 等对象类型必须用 {@code any()} 匹配，因为可能为 null；
 * primitive 参数（boolean/int）必须用对应 primitive matcher（anyBoolean/anyInt）。
 */
@ExtendWith(MockitoExtension.class)
class AgentNodeExecutorTest {

    @Mock private Planner planner;
    @Mock private StepExecutor stepExecutor;
    private final VariableResolver variableResolver = new VariableResolver();

    private AgentNodeExecutor executor() {
        return new AgentNodeExecutor(planner, stepExecutor, variableResolver);
    }

    /** Planner.generate(Long plannerModelId, String userGoal, List<String> toolNames, boolean hasKb, int maxSteps) */
    private void stubPlanner(PlanDTO plan) {
        when(planner.generate(any(), anyString(), any(), anyBoolean(), anyInt())).thenReturn(plan);
    }

    /** StepExecutor.execute(Long, String, List, Map, Long, int, boolean, String, List) */
    private void stubStep(AgentStepResult first, AgentStepResult second) {
        var stub = when(stepExecutor.execute(any(), anyString(), any(), any(), any(), anyInt(), anyBoolean(), anyString(), any()));
        if (second != null) {
            stub.thenReturn(first, second);
        } else {
            stub.thenReturn(first);
        }
    }

    @Test
    void supportedType_isAGENT() {
        assertThat(executor().supportedType()).isEqualTo("AGENT");
    }

    @Test
    void execute_missingGoal_fallsBackToLastOutput() throws Exception {
        stubPlanner(buildPlan("p1", "s", List.of(step("step_1", "title", "desc"))));
        stubStep(successResult("最终回答"), null);

        RunContext ctx = new RunContext();
        ctx.getVars().put("lastOutput", "上游输入");
        GraphNode node = node(Map.of("maxSteps", 3, "timeoutMs", 5000));

        NodeResult result = executor().execute(context(node, ctx));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).containsEntry("text", "最终回答");
        assertThat(result.getOutput()).containsEntry("planSummary", "s");
        assertThat(result.getOutput()).containsEntry("stepCount", 1);
        assertThat(result.getOutput()).containsEntry("status", "SUCCESS");
    }

    @Test
    void execute_withGoal_rendersVariables() throws Exception {
        stubPlanner(buildPlan("p2", "生成报告", List.of(step("s1", "搜索", "desc"))));
        stubStep(successResult("报告完成"), null);

        RunContext ctx = new RunContext();
        ctx.getVars().put("input", "AI");
        GraphNode node = node(Map.of("goal", "写关于 {{input}} 的报告", "maxSteps", 3, "timeoutMs", 5000));

        NodeResult result = executor().execute(context(node, ctx));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).containsEntry("text", "报告完成");
    }

    @Test
    void execute_noGoalAndNoLastOutput_throws() {
        RunContext ctx = new RunContext();
        GraphNode node = node(Map.of("maxSteps", 3, "timeoutMs", 5000));

        assertThatThrownBy(() -> executor().execute(context(node, ctx)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("缺少 goal");
    }

    @Test
    void execute_plannerFails_returnsFailureNodeResult() throws Exception {
        when(planner.generate(any(), anyString(), any(), anyBoolean(), anyInt()))
                .thenThrow(new RuntimeException("LLM 超时"));

        RunContext ctx = new RunContext();
        GraphNode node = node(Map.of("goal", "hi", "timeoutMs", 5000));

        NodeResult result = executor().execute(context(node, ctx));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getOutput()).containsEntry("status", "PLAN_FAILED");
        assertThat(result.getError()).contains("Planner failed");
    }

    @Test
    void execute_stepExecutorFails_returnsPartialResult() throws Exception {
        stubPlanner(buildPlan("p3", "两步骤", List.of(
                step("s1", "第一步", "desc1"),
                step("s2", "第二步", "desc2"))));
        stubStep(successResult("第一步骤结果"), failResult("网络错误"));

        RunContext ctx = new RunContext();
        GraphNode node = node(Map.of("goal", "do", "timeoutMs", 5000));

        NodeResult result = executor().execute(context(node, ctx));

        assertThat(result.getOutput()).containsEntry("stepCount", 2);
        assertThat(result.getOutput()).containsEntry("status", "FAILED");
    }

    // ==================== helpers ====================

    private NodeExecutionContext context(GraphNode node, RunContext ctx) {
        return new NodeExecutionContext(node, ctx, new WorkflowRun(), 1);
    }

    private GraphNode node(Map<String, Object> config) {
        GraphNode n = new GraphNode();
        n.setId("agent_1");
        n.setType("AGENT");
        n.setName("Agent 测试");
        n.setConfig(new LinkedHashMap<>(config));
        return n;
    }

    private PlanDTO buildPlan(String id, String summary, List<PlanDTO.StepDTO> steps) {
        PlanDTO p = new PlanDTO();
        p.setPlanId(id);
        p.setSummary(summary);
        p.setSteps(steps);
        return p;
    }

    private PlanDTO.StepDTO step(String id, String title, String desc) {
        PlanDTO.StepDTO s = new PlanDTO.StepDTO();
        s.setStepId(id);
        s.setTitle(title);
        s.setDescription(desc);
        s.setStepIndex(1);
        return s;
    }

    private AgentStepResult successResult(String text) {
        return AgentStepResult.builder().status("SUCCESS").finalText(text).resultSummary(text).build();
    }

    private AgentStepResult failResult(String err) {
        return AgentStepResult.builder().status("FAILED").errorMessage(err).finalText("").build();
    }
}
