package com.wgh.rag.agentmulti.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.agentmulti.crew.CrewCoordinator;
import com.wgh.rag.agentmulti.graph.GraphCoordinator;
import com.wgh.rag.agentmulti.groupchat.GroupChatCoordinator;
import com.wgh.rag.agentmulti.mapper.MultiAgentRunMapper;
import com.wgh.rag.agentmulti.mapper.MultiAgentStepMapper;
import com.wgh.rag.agentmulti.service.MultiAgentService;
import com.wgh.rag.agentmulti.support.SseEventSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MultiAgentRuntime 单元测试 —— 仅覆盖无依赖方法 newRunId()。
 * 构造器中的 8 个依赖全部 mock，方法本身不触碰任何依赖。
 */
@ExtendWith(MockitoExtension.class)
class MultiAgentRuntimeTest {

    @Mock
    private MultiAgentRunMapper runMapper;
    @Mock
    private MultiAgentStepMapper stepMapper;
    @Mock
    private SseEventSender sse;
    @Mock
    private MultiAgentService multiAgentService;
    @Mock
    private CrewCoordinator crewCoordinator;
    @Mock
    private GraphCoordinator graphCoordinator;
    @Mock
    private GroupChatCoordinator groupChatCoordinator;

    private MultiAgentRuntime runtime;

    @BeforeEach
    void setUp() {
        runtime = new MultiAgentRuntime(runMapper, stepMapper, new ObjectMapper(), sse,
                multiAgentService, crewCoordinator, graphCoordinator, groupChatCoordinator);
    }

    /** newRunId：连续生成 100 个互不重复 */
    @Test
    void newRunId_generates100UniqueIds() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            ids.add(runtime.newRunId());
        }

        assertThat(ids).hasSize(100);
    }

    /** newRunId：格式为 "mag-" 前缀 + 16 位小写十六进制字符，总长 20 */
    @Test
    void newRunId_matchesExpectedFormat() {
        String runId = runtime.newRunId();

        assertThat(runId).startsWith("mag-");
        assertThat(runId).hasSize(20);
        assertThat(runId.substring(4)).matches("[0-9a-f]{16}");
    }
}
