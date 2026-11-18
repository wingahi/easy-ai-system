package com.wgh.rag.agent;

import com.wgh.rag.agent.constant.AgentConstants;
import com.wgh.rag.agent.dto.AgentStepResult;
import com.wgh.rag.llm.service.ModelFailoverService;
import com.wgh.rag.config.property.RagProperties;
import com.wgh.rag.kb.service.RagRetrieveService;
import com.wgh.rag.llm.entity.LlmModel;
import com.wgh.rag.llm.factory.ModelCacheManager;
import com.wgh.rag.tool.entity.ToolConfig;
import com.wgh.rag.tool.executor.ToolExecutionEngine;
import com.wgh.rag.tool.fallback.ToolCircuitBreaker;
import com.wgh.rag.tool.fallback.ToolFallbackProperties;
import com.wgh.rag.tool.fallback.ToolInvoker;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 接线测试：确认 StepExecutor 的工具调用真正走了兜底链（重试生效、success 来自结构化结果）。
 */
@DisplayName("StepExecutor 工具兜底接线")
class StepExecutorToolFallbackTest {

    @Test
    @DisplayName("可重试错误在 StepExecutor 内重试并成功")
    void retryVisibleThroughStepExecutor() {
        ToolExecutionEngine engine = Mockito.mock(ToolExecutionEngine.class);
        ToolFallbackProperties properties = new ToolFallbackProperties();
        properties.setBackoffMs(1);
        properties.setJitterRatio(0);
        ToolInvoker invoker = new ToolInvoker(engine, properties, new ToolCircuitBreaker(properties));

        ModelCacheManager cache = Mockito.mock(ModelCacheManager.class);
        RagRetrieveService retrieve = Mockito.mock(RagRetrieveService.class);
        ModelFailoverService failover = Mockito.mock(ModelFailoverService.class);
        StepExecutor executor = new StepExecutor(engine, invoker, cache, retrieve, failover, new RagProperties());

        LlmModel model = new LlmModel();
        model.setId(1L);
        model.setModelName("m");
        when(failover.resolveChain(1L)).thenReturn(List.of(model));

        ChatModel chatModel = Mockito.mock(ChatModel.class);
        when(cache.getChatModel(1L)).thenReturn(chatModel);
        ToolExecutionRequest call = ToolExecutionRequest.builder()
                .id("c1").name("t").arguments("{}").build();
        when(chatModel.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.builder()
                        .toolExecutionRequests(List.of(call)).build()).build())
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("done")).build());

        ToolConfig config = new ToolConfig();
        config.setId(5L);
        config.setName("t");
        config.setToolType("HTTP");
        config.setExecutorConfig("{\"method\":\"GET\"}");
        when(engine.execute(any(), any(), any()))
                .thenReturn(new ToolExecutionResultMessage("c1", "t", "Error: HttpTimeoutException - timeout"))
                .thenReturn(new ToolExecutionResultMessage("c1", "t", "ok"));

        AgentStepResult result = executor.execute(
                1L, "hello", List.of(), Map.of("t", config), 1L, 3, false, null, List.of());

        assertThat(result.getStatus()).isEqualTo(AgentConstants.STATUS_SUCCESS);
        assertThat(result.getToolInvocations()).hasSize(1);
        assertThat(result.getToolInvocations().get(0).isSuccess()).isTrue();
        verify(engine, times(2)).execute(any(), any(), any());
    }

    @Test
    @DisplayName("不可重试错误在 StepExecutor 内只执行一次且 success=false")
    void authFailureNotRetriedThroughStepExecutor() {
        ToolExecutionEngine engine = Mockito.mock(ToolExecutionEngine.class);
        ToolFallbackProperties properties = new ToolFallbackProperties();
        properties.setBackoffMs(1);
        properties.setJitterRatio(0);
        ToolInvoker invoker = new ToolInvoker(engine, properties, new ToolCircuitBreaker(properties));

        ModelCacheManager cache = Mockito.mock(ModelCacheManager.class);
        RagRetrieveService retrieve = Mockito.mock(RagRetrieveService.class);
        ModelFailoverService failover = Mockito.mock(ModelFailoverService.class);
        StepExecutor executor = new StepExecutor(engine, invoker, cache, retrieve, failover, new RagProperties());

        LlmModel model = new LlmModel();
        model.setId(1L);
        model.setModelName("m");
        when(failover.resolveChain(1L)).thenReturn(List.of(model));

        ChatModel chatModel = Mockito.mock(ChatModel.class);
        when(cache.getChatModel(1L)).thenReturn(chatModel);
        ToolExecutionRequest call = ToolExecutionRequest.builder()
                .id("c1").name("t").arguments("{}").build();
        when(chatModel.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.builder()
                        .toolExecutionRequests(List.of(call)).build()).build())
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("done")).build());

        ToolConfig config = new ToolConfig();
        config.setId(6L);
        config.setName("t");
        config.setToolType("HTTP");
        config.setExecutorConfig("{\"method\":\"GET\"}");
        when(engine.execute(any(), any(), any()))
                .thenReturn(new ToolExecutionResultMessage("c1", "t", "HTTP 401 - unauthorized"));

        AgentStepResult result = executor.execute(
                1L, "hello", List.of(), Map.of("t", config), 1L, 3, false, null, List.of());

        assertThat(result.getToolInvocations()).hasSize(1);
        assertThat(result.getToolInvocations().get(0).isSuccess()).isFalse();
        verify(engine, times(1)).execute(any(), any(), any());
    }
}
