package com.wgh.rag.agentmulti.support;

import com.wgh.rag.agentmulti.dto.AgentInstance;
import com.wgh.rag.agentmulti.dto.AgentInstanceResult;
import com.wgh.rag.config.property.RagProperties;
import com.wgh.rag.kb.service.RagRetrieveService;
import com.wgh.rag.llm.factory.ModelCacheManager;
import com.wgh.rag.tool.entity.ToolConfig;
import com.wgh.rag.tool.executor.ToolExecutionEngine;
import com.wgh.rag.tool.fallback.ToolCircuitBreaker;
import com.wgh.rag.tool.fallback.ToolFallbackProperties;
import com.wgh.rag.tool.fallback.ToolInvoker;
import com.wgh.rag.tool.service.ToolConfigService;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 接线测试：确认多 Agent 的工具调用经过兜底链，且 success 来自结构化结果。
 */
@DisplayName("AgentInstanceExecutor 兜底接线")
class AgentInstanceExecutorFallbackTest {

    private ToolExecutionEngine engine;
    private ChatModel chatModel;
    private AgentInstanceExecutor executor;

    @BeforeEach
    void setUp() {
        engine = Mockito.mock(ToolExecutionEngine.class);
        ToolFallbackProperties properties = new ToolFallbackProperties();
        properties.setBackoffMs(1);
        properties.setJitterRatio(0);
        ToolInvoker toolInvoker = new ToolInvoker(engine, properties, new ToolCircuitBreaker(properties));

        ModelCacheManager cache = Mockito.mock(ModelCacheManager.class);
        chatModel = Mockito.mock(ChatModel.class);
        when(cache.getChatModel(1L)).thenReturn(chatModel);

        executor = new AgentInstanceExecutor(
                cache,
                engine,
                toolInvoker,
                Mockito.mock(ToolConfigService.class),
                Mockito.mock(RagRetrieveService.class),
                Mockito.mock(SseEventSender.class),
                new RagProperties(),
                Mockito.mock(com.wgh.rag.llm.service.ModelFailoverService.class),
                new com.wgh.rag.agentmulti.config.MultiAgentContextProperties());
    }

    private AgentInstance agent() {
        AgentInstance agent = new AgentInstance();
        agent.setAgentId("a1");
        agent.setName("测试 Agent");
        agent.setRole("执行");
        agent.setChatModelId(1L);
        return agent;
    }

    private void mockToolThenAnswer(ToolExecutionResultMessage toolResult) {
        ToolExecutionRequest call = ToolExecutionRequest.builder()
                .id("c1").name("t").arguments("{}").build();
        when(chatModel.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.builder()
                        .toolExecutionRequests(List.of(call)).build()).build())
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("完成")).build());
        when(engine.execute(any(), any(), any())).thenReturn(toolResult);
    }

    @Test
    @DisplayName("工具成功 - success 来自兜底链结果")
    void successFromOutcome() {
        mockToolThenAnswer(new ToolExecutionResultMessage("c1", "t", "ok"));

        AgentInstanceResult result = executor.execute(agent(), "你好", null, null);

        assertThat(result.getOutput()).isEqualTo("完成");
        assertThat(result.getToolInvocations()).hasSize(1);
        assertThat(result.getToolInvocations().get(0).isSuccess()).isTrue();
    }

    @Test
    @DisplayName("鉴权失败 - 不重试且 success=false")
    void authFailureNotRetried() {
        mockToolThenAnswer(new ToolExecutionResultMessage("c1", "t", "HTTP 401 - unauthorized"));

        AgentInstanceResult result = executor.execute(agent(), "你好", null, null);

        assertThat(result.getOutput()).isEqualTo("完成");
        assertThat(result.getToolInvocations()).hasSize(1);
        assertThat(result.getToolInvocations().get(0).isSuccess()).isFalse();
    }
}
