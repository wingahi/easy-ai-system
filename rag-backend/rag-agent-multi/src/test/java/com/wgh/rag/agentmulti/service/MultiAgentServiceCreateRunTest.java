package com.wgh.rag.agentmulti.service;

import com.wgh.rag.agentmulti.dto.RunCreateRequest;
import com.wgh.rag.agentmulti.entity.MultiAgentCrew;
import com.wgh.rag.agentmulti.entity.MultiAgentRun;
import com.wgh.rag.agentmulti.mapper.MultiAgentCrewMapper;
import com.wgh.rag.agentmulti.mapper.MultiAgentRunMapper;
import com.wgh.rag.agentmulti.mapper.MultiAgentStepMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MultiAgentService.createRun 装配行为测试（R9 下沉后）——
 * 锁定字段装配语义：默认值填充、crewId 模板补齐、显式字段覆盖模板。
 */
@ExtendWith(MockitoExtension.class)
class MultiAgentServiceCreateRunTest {

    @Mock
    private MultiAgentRunMapper runMapper;
    @Mock
    private MultiAgentStepMapper stepMapper;
    @Mock
    private MultiAgentCrewMapper crewMapper;

    private MultiAgentService service;

    @BeforeEach
    void setUp() {
        service = new MultiAgentService(runMapper, stepMapper, crewMapper);
    }

    private RunCreateRequest minimalReq() {
        RunCreateRequest req = new RunCreateRequest();
        req.setRunId("mag-test-0001");
        req.setInput("研究RAG");
        req.setUserGoal("研究RAG");
        req.setExecutionMode("CREW_SEQUENTIAL");
        return req;
    }

    private MultiAgentRun captureInsertedRun() {
        ArgumentCaptor<MultiAgentRun> captor = ArgumentCaptor.forClass(MultiAgentRun.class);
        verify(runMapper).insert(captor.capture());
        return captor.getValue();
    }

    /** 最小入参：chatWindowId=0、crewConfigJson="{}"、agentsJson="[]"、PLANNING、currentRound=0、maxRounds=10 */
    @Test
    void createRun_minimalInput_fillsDefaults() {
        MultiAgentRun run = service.createRun(minimalReq());

        assertThat(run.getRunId()).isEqualTo("mag-test-0001");
        assertThat(run.getChatWindowId()).isEqualTo(0L);
        assertThat(run.getCrewConfigJson()).isEqualTo("{}");
        assertThat(run.getAgentsJson()).isEqualTo("[]");
        assertThat(run.getStatus()).isEqualTo("PLANNING");
        assertThat(run.getCurrentRound()).isEqualTo(0);
        assertThat(run.getMaxRounds()).isEqualTo(10);
        assertThat(run.getUserGoal()).isEqualTo("研究RAG");
        assertThat(run.getInputText()).isEqualTo("研究RAG");
        assertThat(run.getExecutionMode()).isEqualTo("CREW_SEQUENTIAL");
        assertThat(run.getStartTime()).isNotNull();
        // 落库字段与返回值一致
        MultiAgentRun inserted = captureInsertedRun();
        assertThat(inserted).isSameAs(run);
    }

    /** crewId 存在：从 Crew 模板补齐 agentsJson / crewConfigJson / executionMode */
    @Test
    void createRun_crewIdExists_fillsFromTemplate() {
        MultiAgentCrew crew = new MultiAgentCrew();
        crew.setCrewConfigJson("{\"k\":1}");
        crew.setAgentsJson("[{\"agentId\":\"a1\"}]");
        crew.setExecutionMode("CREW_PARALLEL");
        when(crewMapper.selectById(7L)).thenReturn(crew);

        RunCreateRequest req = minimalReq();
        req.setCrewId(7L);
        MultiAgentRun run = service.createRun(req);

        assertThat(run.getCrewConfigJson()).isEqualTo("{\"k\":1}");
        assertThat(run.getAgentsJson()).isEqualTo("[{\"agentId\":\"a1\"}]");
        assertThat(run.getExecutionMode()).isEqualTo("CREW_PARALLEL");
    }

    /** crewId 不存在：保持默认值 */
    @Test
    void createRun_crewIdMissing_keepsDefaults() {
        when(crewMapper.selectById(99L)).thenReturn(null);

        RunCreateRequest req = minimalReq();
        req.setCrewId(99L);
        MultiAgentRun run = service.createRun(req);

        assertThat(run.getCrewConfigJson()).isEqualTo("{}");
        assertThat(run.getAgentsJson()).isEqualTo("[]");
        assertThat(run.getExecutionMode()).isEqualTo("CREW_SEQUENTIAL");
    }

    /**
     * 显式传 agentsJson / crewConfigJson：覆盖 Crew 模板。
     * 现状 quirks 钉住：executionMode 显式传入时不覆盖 Crew 模板（模板优先），
     * 与 crewConfigJson/agentsJson 的覆盖方向不一致。
     */
    @Test
    void createRun_explicitFields_overrideTemplate() {
        MultiAgentCrew crew = new MultiAgentCrew();
        crew.setCrewConfigJson("{\"tpl\":true}");
        crew.setAgentsJson("[{\"agentId\":\"tpl\"}]");
        crew.setExecutionMode("CREW_PARALLEL");
        when(crewMapper.selectById(7L)).thenReturn(crew);

        RunCreateRequest req = minimalReq();
        req.setCrewId(7L);
        req.setAgentsJson("[{\"agentId\":\"a9\"}]");
        req.setCrewConfigJson("{\"mine\":1}");
        req.setExecutionMode("GROUP_CHAT_ROUND_ROBIN");
        req.setMaxRounds(3);
        req.setChatWindowId(42L);
        MultiAgentRun run = service.createRun(req);

        assertThat(run.getAgentsJson()).isEqualTo("[{\"agentId\":\"a9\"}]");
        assertThat(run.getCrewConfigJson()).isEqualTo("{\"mine\":1}");
        // 现状：crew 模板 executionMode 优先于显式入参
        assertThat(run.getExecutionMode()).isEqualTo("CREW_PARALLEL");
        assertThat(run.getMaxRounds()).isEqualTo(3);
        assertThat(run.getChatWindowId()).isEqualTo(42L);
    }

    /** crew 的 crewConfigJson 为空时回退用 graphJson 填充 */
    @Test
    void createRun_crewConfigBlank_fallsBackToGraphJson() {
        MultiAgentCrew crew = new MultiAgentCrew();
        crew.setCrewConfigJson(null);
        crew.setGraphJson("{\"nodes\":[]}");
        crew.setAgentsJson("[]");
        when(crewMapper.selectById(8L)).thenReturn(crew);

        RunCreateRequest req = minimalReq();
        req.setCrewId(8L);
        MultiAgentRun run = service.createRun(req);

        assertThat(run.getCrewConfigJson()).isEqualTo("{\"nodes\":[]}");
    }
}
