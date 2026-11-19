package com.wgh.rag.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.agent.config.AgentProperties;
import com.wgh.rag.agent.constant.AgentConstants;
import com.wgh.rag.agent.entity.AgentPlan;
import com.wgh.rag.agent.entity.AgentStep;
import com.wgh.rag.agent.mapper.AgentPlanMapper;
import com.wgh.rag.agent.mapper.AgentStepMapper;
import com.wgh.rag.agent.service.AgentOrchestrator;
import com.wgh.rag.agent.service.PlanLifecycleService;
import com.wgh.rag.agentmulti.mapper.MultiAgentRunMapper;
import com.wgh.rag.agentmulti.mapper.MultiAgentStepMapper;
import com.wgh.rag.agentmulti.runtime.MultiAgentRuntime;
import com.wgh.rag.agentmulti.service.MultiAgentService;
import com.wgh.rag.chat.mapper.ChatSessionMapper;
import com.wgh.rag.chat.service.ChatWindowService;
import com.wgh.rag.chat.service.RagChatService;
import com.wgh.rag.common.metrics.RagMetrics;
import com.wgh.rag.config.redis.RedisDistributedLock;
import com.wgh.rag.config.redis.RedisUnavailableException;
import com.wgh.rag.controller.service.TaskRecoveryService;
import com.wgh.rag.llm.service.ModelRouter;
import com.wgh.rag.log.service.AuditLogService;
import com.wgh.rag.workflow.constant.WorkflowConstants;
import com.wgh.rag.workflow.dto.GraphEdge;
import com.wgh.rag.workflow.dto.GraphNode;
import com.wgh.rag.workflow.dto.RunContext;
import com.wgh.rag.workflow.dto.WorkflowGraph;
import com.wgh.rag.workflow.engine.GraphValidator;
import com.wgh.rag.workflow.engine.WorkflowEngine;
import com.wgh.rag.workflow.entity.WorkflowNodeRun;
import com.wgh.rag.workflow.entity.WorkflowRun;
import com.wgh.rag.workflow.mapper.WorkflowMapper;
import com.wgh.rag.workflow.mapper.WorkflowNodeRunMapper;
import com.wgh.rag.workflow.mapper.WorkflowRunMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskRecoveryServiceTest {

    @Mock private AgentPlanMapper agentPlanMapper;
    @Mock private AgentStepMapper agentStepMapper;
    @Mock private AgentOrchestrator agentOrchestrator;
    @Mock private PlanLifecycleService planLifecycleService;
    @Mock private AgentProperties agentProperties;
    @Mock private ModelRouter modelRouter;
    @Mock private ChatWindowService chatWindowService;
    @Mock private WorkflowRunMapper workflowRunMapper;
    @Mock private WorkflowNodeRunMapper workflowNodeRunMapper;
    @Mock private WorkflowMapper workflowMapper;
    @Mock private WorkflowEngine workflowEngine;
    @Mock private GraphValidator graphValidator;
    @Mock private MultiAgentRunMapper multiAgentRunMapper;
    @Mock private MultiAgentStepMapper multiAgentStepMapper;
    @Mock private MultiAgentService multiAgentService;
    @Mock private MultiAgentRuntime multiAgentRuntime;
    @Mock private ChatSessionMapper chatSessionMapper;
    @Mock private RagChatService ragChatService;
    @Mock private AuditLogService auditLogService;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();
    @Mock private RedisDistributedLock redisDistributedLock;
    @Mock private RagMetrics metrics;

    @InjectMocks private TaskRecoveryService service;

    @Test
    void redisLockContentionReturnsAlreadyResumingOnOtherInstance() {
        when(agentPlanMapper.selectById(1L)).thenReturn(runningPlan());
        when(redisDistributedLock.enabled()).thenReturn(true);
        when(redisDistributedLock.tryAcquire(anyString())).thenReturn(null);

        Map<String, Object> result = service.resumeTask("AGENT", "1");

        assertEquals(false, result.get("success"));
        assertTrue(String.valueOf(result.get("message")).contains("其他实例"));
        verifyNoInteractions(agentOrchestrator, auditLogService);
    }

    @Test
    void redisDisabledRejectsResumeWithoutDispatch() {
        when(redisDistributedLock.enabled()).thenReturn(false);
        when(agentPlanMapper.selectById(1L)).thenReturn(runningPlan());

        Map<String, Object> result = service.resumeTask("AGENT", "1");

        assertEquals(false, result.get("success"));
        assertTrue(String.valueOf(result.get("message")).contains("分布式锁不可用"));
        verify(metrics, times(1)).recordStateStoreFallback("task-recovery-lock", "disabled");
        verify(redisDistributedLock, never()).tryAcquire(anyString());
        verifyNoInteractions(agentOrchestrator, agentStepMapper, auditLogService);
    }

    @Test
    void redisUnavailableRejectsResumeWithoutDispatch() {
        when(redisDistributedLock.enabled()).thenReturn(true);
        when(redisDistributedLock.tryAcquire(anyString()))
                .thenThrow(new RedisUnavailableException("redis down"));
        when(agentPlanMapper.selectById(1L)).thenReturn(runningPlan());

        Map<String, Object> result = service.resumeTask("AGENT", "1");

        assertEquals(false, result.get("success"));
        assertTrue(String.valueOf(result.get("message")).contains("分布式锁不可用"));
        verify(metrics, times(1)).recordStateStoreFallback("task-recovery-lock", "error");
        verifyNoInteractions(planLifecycleService, agentOrchestrator, auditLogService);
    }

    @Test
    void workflowConditionKeepsUnselectedBranchOutOfReady() throws Exception {
        WorkflowGraph graph = graph(
                List.of(node("start", WorkflowConstants.NODE_START),
                        node("cond", WorkflowConstants.NODE_CONDITION),
                        node("a", WorkflowConstants.NODE_LLM),
                        node("b", WorkflowConstants.NODE_LLM),
                        node("end", WorkflowConstants.NODE_END)),
                List.of(edge("start", "cond", null),
                        edge("cond", "a", "true"),
                        edge("cond", "b", "false"),
                        edge("a", "end", null),
                        edge("b", "end", null)));
        mockWorkflow(graph, List.of(
                nodeRun("start", WorkflowConstants.STATUS_SUCCESS, Map.of("text", "in")),
                nodeRun("cond", WorkflowConstants.STATUS_SUCCESS, Map.of("result", false))));
        mockWorkflowLock();

        Map<String, Object> result = service.resumeTask("WORKFLOW", "100");
        assertEquals(true, result.get("success"));

        ArgumentCaptor<RunContext> ctxCaptor = ArgumentCaptor.forClass(RunContext.class);
        verify(workflowEngine, timeout(2000)).resume(any(), any(), any(), ctxCaptor.capture(), any());
        assertEquals(List.of("b"), ctxCaptor.getValue().getPendingNodeIds());
    }

    @Test
    void workflowParallelJoinAndStaleRunningNode() throws Exception {
        WorkflowGraph graph = graph(
                List.of(node("start", WorkflowConstants.NODE_START),
                        node("a", WorkflowConstants.NODE_LLM),
                        node("b", WorkflowConstants.NODE_LLM),
                        node("c", WorkflowConstants.NODE_LLM),
                        node("end", WorkflowConstants.NODE_END)),
                List.of(edge("start", "a", null), edge("start", "b", null),
                        edge("a", "c", null), edge("b", "c", null),
                        edge("c", "end", null)));
        mockWorkflow(graph, List.of(
                nodeRun("start", WorkflowConstants.STATUS_SUCCESS, Map.of("text", "in")),
                nodeRun("a", WorkflowConstants.STATUS_SUCCESS, Map.of("text", "A done")),
                nodeRun("b", WorkflowConstants.STATUS_RUNNING, null)));
        mockWorkflowLock();

        service.resumeTask("WORKFLOW", "100");

        ArgumentCaptor<RunContext> ctxCaptor = ArgumentCaptor.forClass(RunContext.class);
        verify(workflowEngine, timeout(2000)).resume(any(), any(), any(), ctxCaptor.capture(), any());
        assertEquals(List.of("b"), ctxCaptor.getValue().getPendingNodeIds());

        ArgumentCaptor<WorkflowNodeRun> nrCaptor = ArgumentCaptor.forClass(WorkflowNodeRun.class);
        verify(workflowNodeRunMapper).updateById(nrCaptor.capture());
        assertEquals("b", nrCaptor.getValue().getNodeId());
        assertEquals(WorkflowConstants.STATUS_FAILED, nrCaptor.getValue().getStatus());
        assertEquals("interrupted by restart", nrCaptor.getValue().getErrorMessage());

        // 重复恢复：已完成节点仍不会被重复执行
        verify(redisDistributedLock, timeout(2000)).unlock(any());
        service.resumeTask("WORKFLOW", "100");
        ArgumentCaptor<RunContext> again = ArgumentCaptor.forClass(RunContext.class);
        verify(workflowEngine, timeout(2000).times(2)).resume(any(), any(), any(), again.capture(), any());
        assertEquals(List.of("b"), again.getAllValues().get(1).getPendingNodeIds());
        verify(workflowNodeRunMapper, never()).insert(any(WorkflowNodeRun.class));
    }

    @Test
    void workflowLoopBreakMakesOnlyDoneBranchReady() throws Exception {
        WorkflowGraph graph = graph(
                List.of(node("start", WorkflowConstants.NODE_START),
                        node("loop", WorkflowConstants.NODE_LOOP),
                        node("body", WorkflowConstants.NODE_LLM),
                        node("end", WorkflowConstants.NODE_END)),
                List.of(edge("start", "loop", null),
                        edge("loop", "body", "loop"),
                        edge("loop", "end", "done"),
                        edge("body", "loop", null)));
        mockWorkflow(graph, List.of(
                nodeRun("start", WorkflowConstants.STATUS_SUCCESS, Map.of("text", "in")),
                nodeRun("loop", WorkflowConstants.STATUS_SUCCESS,
                        Map.of("iteration", 2, "shouldBreak", true, "handle", "done"))));
        mockWorkflowLock();

        service.resumeTask("WORKFLOW", "100");

        ArgumentCaptor<RunContext> ctxCaptor = ArgumentCaptor.forClass(RunContext.class);
        verify(workflowEngine, timeout(2000)).resume(any(), any(), any(), ctxCaptor.capture(), any());
        assertEquals(List.of("end"), ctxCaptor.getValue().getPendingNodeIds());
        assertFalse(ctxCaptor.getValue().getPendingNodeIds().contains("body"));
    }

    private void mockWorkflowLock() {
        when(redisDistributedLock.enabled()).thenReturn(true);
        when(redisDistributedLock.tryAcquire(anyString())).thenReturn(
                new RedisDistributedLock.LockHandle("key", "name", "owner", Duration.ofSeconds(30)));
        when(redisDistributedLock.unlock(any())).thenReturn(true);
    }

    private WorkflowRun mockWorkflow(WorkflowGraph graph, List<WorkflowNodeRun> nodeRuns) throws Exception {
        WorkflowRun run = new WorkflowRun();
        run.setId(100L);
        run.setStatus(WorkflowConstants.STATUS_RUNNING);
        run.setGraphSnapshot(objectMapper.writeValueAsString(graph));
        run.setContextJson("{}");
        run.setInputText("input");
        run.setStartTime(LocalDateTime.now().minusMinutes(10));
        when(workflowRunMapper.selectById(100L)).thenReturn(run);
        when(graphValidator.validate(any())).thenReturn(new GraphValidator.GraphValidationResult(
                List.of(), Set.of(), Map.of(), Map.of(), graph));
        when(workflowEngine.parseContext(any())).thenReturn(new RunContext());
        when(workflowNodeRunMapper.selectList(any())).thenReturn(nodeRuns);
        return run;
    }

    private WorkflowGraph graph(List<GraphNode> nodes, List<GraphEdge> edges) {
        WorkflowGraph graph = new WorkflowGraph();
        graph.setNodes(nodes);
        graph.setEdges(edges);
        return graph;
    }

    private GraphNode node(String id, String type) {
        GraphNode node = new GraphNode();
        node.setId(id);
        node.setType(type);
        return node;
    }

    private GraphEdge edge(String source, String target, String sourceHandle) {
        GraphEdge edge = new GraphEdge();
        edge.setSource(source);
        edge.setTarget(target);
        edge.setSourceHandle(sourceHandle);
        return edge;
    }

    private WorkflowNodeRun nodeRun(String nodeId, String status, Map<String, Object> output) throws Exception {
        WorkflowNodeRun run = new WorkflowNodeRun();
        run.setRunId(100L);
        run.setNodeId(nodeId);
        run.setStatus(status);
        if (output != null) run.setOutputJson(objectMapper.writeValueAsString(output));
        run.setEndTime(LocalDateTime.now());
        return run;
    }

    private AgentPlan runningPlan() {
        AgentPlan plan = new AgentPlan();
        plan.setId(1L);
        plan.setStatus(AgentConstants.STATUS_RUNNING);
        plan.setSessionId(10L);
        plan.setChatWindowId(20L);
        plan.setLlmModelId(30L);
        return plan;
    }

    private AgentStep pendingStep() {
        AgentStep step = new AgentStep();
        step.setPlanId(1L);
        step.setStepId("s1");
        step.setStepIndex(1);
        step.setTitle("步骤一");
        step.setStatus(AgentConstants.STATUS_PENDING);
        return step;
    }
}
