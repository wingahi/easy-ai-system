package com.wgh.rag.workflow.engine.executor;

import com.wgh.rag.common.metrics.RagMetrics;
import com.wgh.rag.llm.entity.LlmModel;
import com.wgh.rag.llm.factory.ModelCacheManager;
import com.wgh.rag.llm.service.ModelFailoverService;
import com.wgh.rag.llm.util.TokenEstimator;
import com.wgh.rag.workflow.config.WorkflowContextProperties;
import com.wgh.rag.workflow.dto.NodeResult;
import com.wgh.rag.workflow.dto.RunContext;
import com.wgh.rag.workflow.engine.VariableResolver;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.data.message.AiMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 接线测试：工作流 LLM 节点真实渲染后按「窗口 × 比例」裁剪输入。
 */
@DisplayName("LLM 节点输入预算")
class LlmNodeExecutorBudgetTest {

    private static final String TRUNCATE_MARK_SUFFIX = "已截断）";

    private ModelCacheManager cache;
    private ModelFailoverService failover;
    private RagMetrics metrics;
    private LlmNodeExecutor executor;
    private ChatModel chatModel;
    private NodeExecutionContext context;

    @BeforeEach
    void setUp() {
        cache = Mockito.mock(ModelCacheManager.class);
        failover = Mockito.mock(ModelFailoverService.class);
        metrics = Mockito.mock(RagMetrics.class);
        WorkflowContextProperties properties = new WorkflowContextProperties();
        executor = new LlmNodeExecutor(new VariableResolver(), cache, failover, properties, metrics);

        LlmModel model = new LlmModel();
        model.setId(1L);
        model.setModelName("m");
        model.setMaxContextTokens(100);
        when(failover.resolveChain(1L)).thenReturn(List.of(model));

        chatModel = Mockito.mock(ChatModel.class);
        when(cache.getChatModel(1L)).thenReturn(chatModel);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(
                ChatResponse.builder().aiMessage(AiMessage.from("ok")).build());

        context = Mockito.mock(NodeExecutionContext.class);
        when(context.getRunContext()).thenReturn(new RunContext());
    }

    private void givenPrompt(String prompt) {
        when(context.config()).thenReturn(Map.of("modelId", 1L, "prompt", prompt));
    }

    private ChatRequest capturedRequest() {
        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatModel).chat(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("超窗 - 裁剪到窗口×0.7 并标注截断、记录指标")
    void overBudget_trimsPrompt() {
        givenPrompt("a".repeat(400)); // 100 tokens

        NodeResult result = executor.execute(context);

        String sent = ((UserMessage) capturedRequest().messages().get(0)).singleText();
        assertThat(sent).endsWith(TRUNCATE_MARK_SUFFIX);
        assertThat(TokenEstimator.estimate(sent)).isLessThanOrEqualTo(70);
        assertThat(result.getOutput()).containsEntry("text", "ok");
        verify(metrics).recordWorkflowNodePromptTrim(anyInt(), anyInt());
    }

    @Test
    @DisplayName("未超窗 - 原样发送、不记裁剪指标")
    void withinBudget_noTrim() {
        String prompt = "a".repeat(40); // 10 tokens
        givenPrompt(prompt);

        NodeResult result = executor.execute(context);

        String sent = ((UserMessage) capturedRequest().messages().get(0)).singleText();
        assertThat(sent).isEqualTo(prompt);
        assertThat(result.getOutput()).containsEntry("text", "ok");
        verify(metrics, never()).recordWorkflowNodePromptTrim(anyInt(), anyInt());
    }

    @Test
    @DisplayName("模型未配置窗口 - 使用兜底窗口，不裁剪")
    void missingModelWindow_usesFallback() {
        LlmModel model = new LlmModel();
        model.setId(2L);
        model.setModelName("m2");
        when(failover.resolveChain(2L)).thenReturn(List.of(model));
        when(cache.getChatModel(2L)).thenReturn(chatModel);
        String prompt = "a".repeat(400); // 100 tokens，远小于兜底 8192
        when(context.config()).thenReturn(Map.of("modelId", 2L, "prompt", prompt));

        executor.execute(context);

        String sent = ((UserMessage) capturedRequest().messages().get(0)).singleText();
        assertThat(sent).isEqualTo(prompt);
        verify(metrics, never()).recordWorkflowNodePromptTrim(anyInt(), anyInt());
    }
}
