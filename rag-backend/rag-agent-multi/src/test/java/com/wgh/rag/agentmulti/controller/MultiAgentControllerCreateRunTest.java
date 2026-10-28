package com.wgh.rag.agentmulti.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.agentmulti.dto.RunCreateRequest;
import com.wgh.rag.agentmulti.entity.MultiAgentRun;
import com.wgh.rag.agentmulti.mapper.MultiAgentCrewMapper;
import com.wgh.rag.agentmulti.mapper.MultiAgentStepMapper;
import com.wgh.rag.agentmulti.runtime.MultiAgentRuntime;
import com.wgh.rag.agentmulti.service.MultiAgentService;
import com.wgh.rag.common.result.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * MultiAgentController.createRun 轻量委托测试（R9 后）——
 * Controller 只做参数解析并委托 MultiAgentService，响应包装 runId/status。
 */
@ExtendWith(MockitoExtension.class)
class MultiAgentControllerCreateRunTest {

    @Mock
    private MultiAgentRuntime runtime;
    @Mock
    private MultiAgentService service;
    @Mock
    private MultiAgentStepMapper stepMapper;
    @Mock
    private MultiAgentCrewMapper crewMapper;

    private MultiAgentController controller;

    @BeforeEach
    void setUp() {
        controller = new MultiAgentController(runtime, service, stepMapper, crewMapper, new ObjectMapper());
        when(runtime.newRunId()).thenReturn("mag-test-0001");
    }

    /** 委托：body 解析为 RunCreateRequest，响应包装 runId/status */
    @Test
    @SuppressWarnings("unchecked")
    void createRun_delegatesToServiceAndWrapsResponse() {
        MultiAgentRun persisted = new MultiAgentRun();
        persisted.setRunId("mag-test-0001");
        persisted.setStatus("PLANNING");
        when(service.createRun(any())).thenReturn(persisted);

        Map<String, Object> body = new HashMap<>(Map.of(
                "input", "研究RAG",
                "chatWindowId", "42",
                "maxRounds", 3,
                "agentsJson", List.of(Map.of("agentId", "a1"))));
        Result<Map<String, Object>> resp = controller.createRun(body);

        assertThat(resp.getData()).containsEntry("runId", "mag-test-0001")
                .containsEntry("status", "PLANNING");

        ArgumentCaptor<RunCreateRequest> captor = ArgumentCaptor.forClass(RunCreateRequest.class);
        org.mockito.Mockito.verify(service).createRun(captor.capture());
        RunCreateRequest req = captor.getValue();
        assertThat(req.getRunId()).isEqualTo("mag-test-0001");
        assertThat(req.getInput()).isEqualTo("研究RAG");
        assertThat(req.getUserGoal()).isEqualTo("研究RAG");
        assertThat(req.getChatWindowId()).isEqualTo(42L);
        assertThat(req.getMaxRounds()).isEqualTo(3);
        assertThat(req.getExecutionMode()).isEqualTo("CREW_SEQUENTIAL");
        // 非字符串 agentsJson 被序列化为 JSON 字符串
        assertThat(req.getAgentsJson()).isEqualTo("[{\"agentId\":\"a1\"}]");
    }
}
