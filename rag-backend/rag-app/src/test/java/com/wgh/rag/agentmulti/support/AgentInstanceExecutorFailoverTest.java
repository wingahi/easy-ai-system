package com.wgh.rag.agentmulti.support;

import com.wgh.rag.agentmulti.config.MultiAgentContextProperties;
import com.wgh.rag.agentmulti.context.MultiAgentContext;
import com.wgh.rag.agentmulti.dto.AgentInstance;
import com.wgh.rag.agentmulti.dto.AgentInstanceResult;
import com.wgh.rag.config.property.RagProperties;
import com.wgh.rag.kb.service.RagRetrieveService;
import com.wgh.rag.llm.entity.LlmModel;
import com.wgh.rag.llm.factory.ModelCacheManager;
import com.wgh.rag.llm.service.ModelFailoverService;
import com.wgh.rag.tool.executor.ToolExecutionEngine;
import com.wgh.rag.tool.fallback.ToolInvoker;
import com.wgh.rag.tool.service.ToolConfigService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;

/**
 * 多 Agent 故障转移接线测试。
 */
@DisplayName("多 Agent 故障转移")
class AgentInstanceExecutorFailoverTest {

    private AgentInstance agent() {
        AgentInstance agent = new AgentInstance();
        agent.setAgentId("a1");
        agent.setName("worker");
        agent.setChatModelId(1L);
        agent.setHasMemory(false);
        return agent;
    }

    @Test
    @DisplayName("主模型失败 - 切换到备用模型并返回结果")
    void switchesToBackupModel() {
        LlmModel primary = new LlmModel();
        primary.setId(1L);
        primary.setModelName("primary");
        LlmModel backup = new LlmModel();
        backup.setId(2L);
        backup.setModelName("backup");

        ModelCacheManager cache = Mockito.mock(ModelCacheManager.class);
        ChatModel failing = Mockito.mock(ChatModel.class);
        ChatModel working = Mockito.mock(ChatModel.class);
        Mockito.when(cache.getChatModel(1L)).thenReturn(failing);
        Mockito.when(cache.getChatModel(2L)).thenReturn(working);
        Mockito.when(failing.chat(Mockito.any(dev.langchain4j.model.chat.request.ChatRequest.class)))
                .thenThrow(new RuntimeException("boom"));
        Mockito.when(working.chat(Mockito.any(dev.langchain4j.model.chat.request.ChatRequest.class)))
                .thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from("备用模型结果")).build());

        ModelFailoverService failoverService = Mockito.mock(ModelFailoverService.class);
        Mockito.when(failoverService.resolveChain(1L)).thenReturn(List.of(primary, backup));

        ToolExecutionEngine engine = Mockito.mock(ToolExecutionEngine.class);
        AgentInstanceExecutor executor = new AgentInstanceExecutor(
                cache, engine, Mockito.mock(ToolInvoker.class),
                Mockito.mock(ToolConfigService.class), Mockito.mock(RagRetrieveService.class),
                Mockito.mock(SseEventSender.class), new RagProperties(),
                failoverService, new MultiAgentContextProperties());

        AgentInstanceResult result = executor.execute(agent(), "你好", new MultiAgentContext(), new SseEmitter());

        assertThat(result.getOutput()).isEqualTo("备用模型结果");
        assertThat(result.getCard()).isNotNull();
        assertThat(result.getCard().getConclusion()).isEqualTo("备用模型结果");
        // 切链只重试模型调用，未触发任何工具执行
        Mockito.verifyNoInteractions(engine);
    }
}
