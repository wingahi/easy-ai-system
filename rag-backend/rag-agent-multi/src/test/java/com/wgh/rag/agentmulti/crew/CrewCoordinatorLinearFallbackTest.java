package com.wgh.rag.agentmulti.crew;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.agentmulti.context.MultiAgentContext;
import com.wgh.rag.agentmulti.dto.AgentInstance;
import com.wgh.rag.agentmulti.dto.AgentInstanceResult;
import com.wgh.rag.agentmulti.entity.MultiAgentRun;
import com.wgh.rag.agentmulti.service.MultiAgentService;
import com.wgh.rag.agentmulti.support.AgentInstanceExecutor;
import com.wgh.rag.agentmulti.support.SseEventSender;
import com.wgh.rag.workflow.engine.ConditionEvaluator;
import com.wgh.rag.workflow.engine.VariableResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CrewCoordinator 空图兜底钉住测试：
 * crew 无 graphJson 时按 agents 顺序合成线性链执行（修复前静默空跑却标 COMPLETED）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CrewCoordinatorLinearFallbackTest {

    @Mock
    private MultiAgentService service;
    @Mock
    private AgentInstanceExecutor agentExecutor;
    @Mock
    private SseEventSender sse;
    @Mock
    private ConditionEvaluator conditionEvaluator;
    @Mock
    private VariableResolver variableResolver;

    private CrewCoordinator coordinator;

    @BeforeEach
    void setUp() {
        coordinator = new CrewCoordinator(service, agentExecutor, sse,
                new ObjectMapper(), conditionEvaluator, variableResolver);
    }

    private MultiAgentRun newRun(String agentsJson) {
        MultiAgentRun run = new MultiAgentRun();
        run.setRunId("mag-test");
        run.setExecutionMode("CREW_SEQUENTIAL");
        run.setUserGoal("目标");
        run.setCrewConfigJson("{\"mode\":\"SEQUENTIAL\"}");
        run.setAgentsJson(agentsJson);
        return run;
    }

    private AgentInstance agent(String id, String name) {
        return AgentInstance.builder().agentId(id).name(name).chatModelId(1L).build();
    }

    private AgentInstanceResult okResult(String output) {
        return AgentInstanceResult.builder().output(output).durationMs(1L).build();
    }

    /** 无 graph：两个 agent 按列表顺序依次执行，最终 COMPLETED */
    @Test
    void execute_emptyGraph_runsAgentsInOrderAndCompletes() {
        String agentsJson = "[{\"agentId\":\"a\",\"name\":\"A\"},{\"agentId\":\"b\",\"name\":\"B\"}]";
        MultiAgentRun run = newRun(agentsJson);
        List<AgentInstance> agents = List.of(agent("a", "A"), agent("b", "B"));

        when(agentExecutor.execute(any(), any(), any(), any()))
                .thenReturn(okResult("out-a"), okResult("out-b"));

        coordinator.execute(run, agents, new MultiAgentContext(new ObjectMapper()), null);

        InOrder inOrder = inOrder(agentExecutor);
        inOrder.verify(agentExecutor).execute(eq(agents.get(0)), any(), any(), any());
        inOrder.verify(agentExecutor).execute(eq(agents.get(1)), any(), any(), any());
        verify(service).finishRun(run, "COMPLETED", null);
        verify(sse).send(eq(null), eq("multi_agent_complete"), any(Object[].class));
    }

    /** 无 graph：每步结果持久化（run_id 写入步骤，依赖 VARCHAR run_id 列） */
    @Test
    void execute_emptyGraph_persistsEachStep() {
        String agentsJson = "[{\"agentId\":\"a\",\"name\":\"A\"},{\"agentId\":\"b\",\"name\":\"B\"}]";
        MultiAgentRun run = newRun(agentsJson);
        List<AgentInstance> agents = List.of(agent("a", "A"), agent("b", "B"));

        when(agentExecutor.execute(any(), any(), any(), any()))
                .thenReturn(okResult("out"));

        coordinator.execute(run, agents, new MultiAgentContext(new ObjectMapper()), null);

        verify(service, times(2)).insertStep(any());
    }
}
