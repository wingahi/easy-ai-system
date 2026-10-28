package com.wgh.rag.workflow.engine.executor;

import com.wgh.rag.llm.factory.ModelCacheManager;
import com.wgh.rag.workflow.dto.GraphNode;
import com.wgh.rag.workflow.dto.NodeResult;
import com.wgh.rag.workflow.dto.RunContext;
import com.wgh.rag.workflow.engine.VariableResolver;
import com.wgh.rag.workflow.entity.WorkflowRun;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * LLM 节点执行器测试 —— modelId 校验、prompt 渲染与回退、消息组装。
 */
@ExtendWith(MockitoExtension.class)
class LlmNodeExecutorTest {

    @Mock
    private ModelCacheManager modelCacheManager;
    @Mock
    private ChatModel chatModel;

    private final VariableResolver variableResolver = new VariableResolver();

    private LlmNodeExecutor executor() {
        return new LlmNodeExecutor(variableResolver, modelCacheManager);
    }

    @Test
    void execute_missingModelId_throws() {
        GraphNode node = node(Map.of("prompt", "hi"));

        assertThatThrownBy(() -> executor().execute(context(node, new RunContext())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("LLM 节点缺少 modelId");
    }

    @Test
    void execute_rendersPromptsAndCallsModel() {
        when(modelCacheManager.getChatModel(7L)).thenReturn(chatModel);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(
                ChatResponse.builder().aiMessage(AiMessage.from("模型回答")).build());
        RunContext ctx = new RunContext();
        ctx.getVars().put("input", "问题");
        GraphNode node = node(Map.of("modelId", 7, "systemPrompt", "你是助手", "prompt", "问：{{input}}"));

        NodeResult result = executor().execute(context(node, ctx));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).containsEntry("text", "模型回答");

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        org.mockito.Mockito.verify(chatModel).chat(captor.capture());
        assertThat(captor.getValue().messages()).hasSize(2);
        assertThat(captor.getValue().messages().get(0)).isInstanceOf(SystemMessage.class);
        assertThat(((SystemMessage) captor.getValue().messages().get(0)).text()).isEqualTo("你是助手");
        assertThat(((UserMessage) captor.getValue().messages().get(1)).singleText()).isEqualTo("问：问题");
    }

    @Test
    void execute_blankPrompt_fallsBackToLastOutput() {
        when(modelCacheManager.getChatModel(7L)).thenReturn(chatModel);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(
                ChatResponse.builder().aiMessage(AiMessage.from("答")).build());
        RunContext ctx = new RunContext();
        ctx.getVars().put("lastOutput", "上游文本");
        GraphNode node = node(Map.of("modelId", 7));

        executor().execute(context(node, ctx));

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        org.mockito.Mockito.verify(chatModel).chat(captor.capture());
        assertThat(captor.getValue().messages()).hasSize(1);
        assertThat(((UserMessage) captor.getValue().messages().get(0)).singleText()).isEqualTo("上游文本");
    }

    private NodeExecutionContext context(GraphNode node, RunContext ctx) {
        return new NodeExecutionContext(node, ctx, new WorkflowRun(), 1);
    }

    private GraphNode node(Map<String, Object> config) {
        GraphNode n = new GraphNode();
        n.setId("l");
        n.setType("LLM");
        n.setConfig(new LinkedHashMap<>(config));
        return n;
    }
}
