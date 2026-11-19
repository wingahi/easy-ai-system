package com.wgh.rag.agentmulti.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.agentmulti.context.MultiAgentContext;
import com.wgh.rag.agentmulti.dto.AgentInstanceResult;
import com.wgh.rag.agentmulti.entity.MultiAgentStep;
import com.wgh.rag.agentmulti.mapper.MultiAgentStepMapper;
import com.wgh.rag.agentmulti.service.AgentStepCheckpointService.CheckpointSession;
import com.wgh.rag.agentmulti.service.AgentStepCheckpointService.StepDecision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 多 Agent 检查点幂等性：已完成跳过、重复恢复不新增行、失败步骤重试、上下文回填。
 */
@ExtendWith(MockitoExtension.class)
class AgentStepCheckpointServiceTest {

    @Mock private MultiAgentStepMapper stepMapper;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();
    @InjectMocks private AgentStepCheckpointService service;

    /** 模拟 wgh_m_agent_step 表，insert/update 直接作用在共享对象上 */
    private final List<MultiAgentStep> table = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(stepMapper.selectList(any())).thenAnswer(inv -> new ArrayList<>(table));
        when(stepMapper.insert(any(MultiAgentStep.class))).thenAnswer(inv -> {
            MultiAgentStep step = inv.getArgument(0);
            step.setId((long) (table.size() + 1));
            table.add(step);
            return 1;
        });
        org.mockito.Mockito.lenient().when(stepMapper.updateById(any(MultiAgentStep.class))).thenAnswer(inv -> 1);
    }

    @Test
    void beginThenCompleteKeepsOneRowAndSkipsOnResume() {
        CheckpointSession session = service.open("run-1");
        StepDecision decision = service.begin(session, "node:a:iter:1", "a", "writer", "worker", 1, "input");

        assertFalse(decision.skip());
        assertEquals("RUNNING", decision.step().getStatus());
        assertEquals(Integer.valueOf(1), decision.step().getStepIndex());
        verify(stepMapper, times(1)).insert(any(MultiAgentStep.class));

        service.complete(session, decision.step(), AgentInstanceResult.builder()
                .output("A done").thought("think").durationMs(12L).build());
        assertEquals("SUCCESS", decision.step().getStatus());
        assertEquals("A done", decision.step().getOutputText());
        verify(stepMapper, times(1)).updateById(any(MultiAgentStep.class));

        // 模拟重启：重新打开会话，同一 stepKey 应直接跳过且不新增行
        CheckpointSession resumed = service.open("run-1");
        StepDecision again = service.begin(resumed, "node:a:iter:1", "a", "writer", "worker", 1, "input");

        assertTrue(again.skip());
        assertEquals("A done", again.step().getOutputText());
        assertEquals(1, table.size());
        verify(stepMapper, times(1)).insert(any(MultiAgentStep.class));
    }

    @Test
    void failedStepIsRetriedInsteadOfSkippedAndNotDuplicated() {
        CheckpointSession session = service.open("run-2");
        StepDecision first = service.begin(session, "node:b:iter:1", "b", "writer", "worker", 1, null);
        service.fail(session, first.step(), "boom");

        CheckpointSession resumed = service.open("run-2");
        StepDecision retry = service.begin(resumed, "node:b:iter:1", "b", "writer", "worker", 1, null);

        assertFalse(retry.skip());
        assertEquals("RUNNING", retry.step().getStatus());
        assertNull(retry.step().getEndTime());
        assertEquals(1, table.size());
        verify(stepMapper, times(1)).insert(any(MultiAgentStep.class));
    }

    @Test
    void newStepsContinueFromMaxStepIndexPlusOne() {
        CheckpointSession session = service.open("run-3");
        table.add(existingStep(1L, 7, "SUCCESS", "node:x:iter:1", "x"));
        CheckpointSession resumed = service.open("run-3");

        StepDecision next = service.begin(resumed, "node:y:iter:1", "y", "writer", "worker", 1, null);

        assertFalse(next.skip());
        assertEquals(Integer.valueOf(8), next.step().getStepIndex());
    }

    @Test
    void repeatedTraceDecisionDoesNotInsertDuplicateRow() {
        CheckpointSession session = service.open("run-4");
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("result", false);

        service.saveTrace(session, "node:cond:iter:1:trace", "cond", "CONDITION", 1, decision);
        service.saveTrace(session, "node:cond:iter:1:trace", "cond", "CONDITION", 1, decision);

        assertEquals(1, table.size());
        verify(stepMapper, times(1)).insert(any(MultiAgentStep.class));

        CheckpointSession resumed = service.open("run-4");
        Map<String, Object> restored = service.traceDecision(resumed, "node:cond:iter:1:trace");
        assertNotNull(restored);
        assertEquals(false, restored.get("result"));
    }

    @Test
    void restoreContextBackfillsNodeAgentTextAndLastOutput() {
        CheckpointSession session = service.open("run-5");
        StepDecision decision = service.begin(session, "node:a:iter:1", "a", "writer", "worker", 1, null);
        service.complete(session, decision.step(), AgentInstanceResult.builder().output("A done").build());

        CheckpointSession resumed = service.open("run-5");
        MultiAgentContext ctx = new MultiAgentContext(objectMapper);
        service.restoreContext(resumed, ctx);

        assertEquals("A done", ctx.get("a.text"));
        assertEquals("A done", ctx.get("writer.text"));
        assertEquals("A done", ctx.getVars("lastOutput"));
    }

    private MultiAgentStep existingStep(Long id, int stepIndex, String status, String stepKey, String agentId) {
        MultiAgentStep step = new MultiAgentStep();
        step.setId(id);
        step.setRunId("run-3");
        step.setAgentId(agentId);
        step.setStepIndex(stepIndex);
        step.setStatus(status);
        step.setInputContext("{\"stepKey\":\"" + stepKey + "\",\"agentId\":\"" + agentId + "\"}");
        return step;
    }
}
