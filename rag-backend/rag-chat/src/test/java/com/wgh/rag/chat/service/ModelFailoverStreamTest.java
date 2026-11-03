package com.wgh.rag.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.agent.config.AgentProperties;
import com.wgh.rag.agent.service.AgentOrchestrator;
import com.wgh.rag.chat.entity.ChatSession;
import com.wgh.rag.chat.entity.ChatWindow;
import com.wgh.rag.chat.evaluation.EvaluationScheme;
import com.wgh.rag.chat.evaluation.JudgeModelSelector;
import com.wgh.rag.chat.evaluation.LlmJudgeService;
import com.wgh.rag.chat.mapper.ChatMessageMapper;
import com.wgh.rag.chat.mapper.ChatSessionMapper;
import com.wgh.rag.chat.mapper.ChatWindowMapper;
import com.wgh.rag.config.property.RagProperties;
import com.wgh.rag.kb.mapper.KnowledgeBaseMapper;
import com.wgh.rag.kb.service.RagRetrieveService;
import com.wgh.rag.kb.service.SemanticCacheService;
import com.wgh.rag.llm.entity.LlmModel;
import com.wgh.rag.llm.factory.ModelCacheManager;
import com.wgh.rag.llm.service.LlmModelService;
import com.wgh.rag.llm.service.ModelPriceService;
import com.wgh.rag.llm.service.ModelRouter;
import com.wgh.rag.common.metrics.RagMetrics;
import com.wgh.rag.quota.service.QuotaService;
import com.wgh.rag.tool.executor.ToolExecutionEngine;
import com.wgh.rag.tool.service.ToolConfigService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 模型故障转移（首 token 前失败切链）单测。
 * 通过 mock StreamingChatModel 在 chat() 内同步触发回调，覆盖：
 * 1) 首模型首 token 前失败 → 切到下一个并成功；
 * 2) 全部失败 → 明确业务错误；
 * 3) 已输出部分内容后失败 → 不切链、不重发、如实报错。
 */
@ExtendWith(MockitoExtension.class)
class ModelFailoverStreamTest {

    @Mock private ChatWindowMapper windowMapper;
    @Mock private ChatSessionMapper sessionMapper;
    @Mock private ChatMessageMapper messageMapper;
    @Mock private RagRetrieveService ragRetrieveService;
    @Mock private ModelCacheManager modelCacheManager;
    @Mock private RagProperties ragProperties;
    @Mock private ObjectMapper objectMapper;
    @Mock private ContextCompressionService contextCompressionService;
    @Mock private LlmJudgeService llmJudgeService;
    @Mock private JudgeModelSelector judgeModelSelector;
    @Mock private ToolExecutionEngine toolExecutionEngine;
    @Mock private ToolConfigService toolConfigService;
    @Mock private AgentOrchestrator agentOrchestrator;
    @Mock private AgentProperties agentProperties;
    @Mock private ChatWorkflowService chatWorkflowService;
    @Mock private QuotaService quotaService;
    @Mock private ModelPriceService modelPriceService;
    @Mock private LlmModelService llmModelService;
    @Mock private SemanticCacheService semanticCacheService;
    @Mock private ChatImageService chatImageService;
    @Mock private PromptTemplateService promptTemplateService;
    @Mock private KnowledgeBaseMapper knowledgeBaseMapper;
    @Mock private ModelRouter modelRouter;
    @Mock private RagMetrics metrics;

    private RagChatService service;
    private ChatSession session;
    private ChatWindow window;

    @BeforeEach
    void setUp() {
        service = new RagChatService(
                windowMapper, sessionMapper, messageMapper, ragRetrieveService, modelCacheManager,
                ragProperties, objectMapper, contextCompressionService, llmJudgeService,
                judgeModelSelector, toolExecutionEngine, toolConfigService, agentOrchestrator,
                agentProperties, chatWorkflowService, quotaService, modelPriceService,
                llmModelService, semanticCacheService, chatImageService, promptTemplateService,
                knowledgeBaseMapper, modelRouter, metrics);
        session = org.mockito.Mockito.mock(ChatSession.class);
        window = org.mockito.Mockito.mock(ChatWindow.class);
    }

    @Test
    void firstModelFailsBeforeFirstToken_switchesToNextAndSucceeds() {
        LlmModel primary = model(1L, "primary");
        LlmModel backup = model(2L, "backup");
        StreamingChatModel firstModel = failingModelBeforeFirstToken("connection refused");
        StreamingChatModel secondModel = answeringModel("答案");
        when(modelCacheManager.getStreamingChatModel(1L)).thenReturn(firstModel);
        when(modelCacheManager.getStreamingChatModel(2L)).thenReturn(secondModel);

        CapturingEmitter emitter = new CapturingEmitter();
        AtomicReference<LlmModel> actualModel = new AtomicReference<>();
        List<LlmModel> switched = new ArrayList<>();

        service.startAttempt(context(List.of(primary, backup), emitter, actualModel, switched), 0);

        assertThat(actualModel.get()).isEqualTo(backup);
        assertThat(switched).containsExactly(backup);
        assertThat(emitter.textOf("content")).containsExactly("答案");
        assertThat(emitter.eventsOf("done")).hasSize(1);
        assertThat(emitter.eventsOf("error")).isEmpty();
        assertThat(emitter.completed).isTrue();
        verify(firstModel, times(1)).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        verify(secondModel, times(1)).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
    }

    @Test
    void allModelsFailBeforeFirstToken_returnsClearBusinessError() {
        LlmModel primary = model(1L, "primary");
        LlmModel backup = model(2L, "backup");
        StreamingChatModel firstModel = failingModelBeforeFirstToken("401 InvalidApiKey");
        StreamingChatModel secondModel = failingModelBeforeFirstToken("timeout");
        when(modelCacheManager.getStreamingChatModel(1L)).thenReturn(firstModel);
        when(modelCacheManager.getStreamingChatModel(2L)).thenReturn(secondModel);

        CapturingEmitter emitter = new CapturingEmitter();
        AtomicReference<LlmModel> actualModel = new AtomicReference<>();
        List<LlmModel> switched = new ArrayList<>();

        service.startAttempt(context(List.of(primary, backup), emitter, actualModel, switched), 0);

        assertThat(emitter.eventsOf("content")).isEmpty();
        assertThat(emitter.eventsOf("done")).isEmpty();
        assertThat(emitter.eventsOf("error")).hasSize(1);
        Map<?, ?> errorPayload = (Map<?, ?>) emitter.eventsOf("error").get(0).data();
        assertThat(String.valueOf(errorPayload.get("text")))
                .startsWith("所有候选模型均调用失败（共 2 个")
                .contains("timeout");
        assertThat(emitter.completed).isTrue();
        verify(firstModel, times(1)).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        verify(secondModel, times(1)).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
    }

    @Test
    void failureAfterPartialOutput_doesNotSwitchAndReportsError() {
        LlmModel primary = model(1L, "primary");
        LlmModel backup = model(2L, "backup");
        StreamingChatModel firstModel = org.mockito.Mockito.mock(StreamingChatModel.class);
        doAnswer(inv -> {
            StreamingChatResponseHandler handler = inv.getArgument(1);
            handler.onPartialResponse("部分");
            handler.onError(new RuntimeException("stream broken"));
            return null;
        }).when(firstModel).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        when(modelCacheManager.getStreamingChatModel(1L)).thenReturn(firstModel);

        CapturingEmitter emitter = new CapturingEmitter();
        AtomicReference<LlmModel> actualModel = new AtomicReference<>();
        List<LlmModel> switched = new ArrayList<>();

        service.startAttempt(context(List.of(primary, backup), emitter, actualModel, switched), 0);

        assertThat(switched).isEmpty();
        assertThat(actualModel.get()).isEqualTo(primary);
        assertThat(emitter.textOf("content")).containsExactly("部分");
        assertThat(emitter.eventsOf("done")).isEmpty();
        assertThat(emitter.eventsOf("error")).hasSize(1);
        assertThat(emitter.completedWithError).isNotNull();
        verify(modelCacheManager, never()).getStreamingChatModel(2L);
    }

    @Test
    void duplicateErrorCallback_doesNotSwitchTwice() {
        LlmModel primary = model(1L, "primary");
        LlmModel backup = model(2L, "backup");
        StreamingChatModel firstModel = org.mockito.Mockito.mock(StreamingChatModel.class);
        doAnswer(inv -> {
            StreamingChatResponseHandler handler = inv.getArgument(1);
            handler.onError(new RuntimeException("first error"));
            handler.onError(new RuntimeException("duplicate error"));
            return null;
        }).when(firstModel).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        when(modelCacheManager.getStreamingChatModel(1L)).thenReturn(firstModel);
        StreamingChatModel secondModel = answeringModel("答案");
        when(modelCacheManager.getStreamingChatModel(2L)).thenReturn(secondModel);

        CapturingEmitter emitter = new CapturingEmitter();
        AtomicReference<LlmModel> actualModel = new AtomicReference<>();
        List<LlmModel> switched = new ArrayList<>();

        service.startAttempt(context(List.of(primary, backup), emitter, actualModel, switched), 0);

        assertThat(switched).containsExactly(backup);
        assertThat(emitter.eventsOf("done")).hasSize(1);
        assertThat(emitter.textOf("content")).containsExactly("答案");
    }

    // ==================== 辅助方法 ====================

    private RagChatService.FailoverContext context(List<LlmModel> chain, SseEmitter emitter,
                                                   AtomicReference<LlmModel> actualModel,
                                                   List<LlmModel> switched) {
        return new RagChatService.FailoverContext(
                chain,
                ChatRequest.builder().messages(new ArrayList<>(List.of(UserMessage.from("hi")))).build(),
                List.of(),
                new ArrayList<>(List.of(UserMessage.from("hi"))),
                emitter,
                List.of(),
                null,
                EvaluationScheme.NONE,
                session,
                window,
                "hi",
                new int[]{0},
                5,
                Map.of(),
                1L,
                new long[]{0L, 0L},
                "tester",
                actualModel,
                (m, reason) -> {
                    switched.add(m);
                    actualModel.set(m);
                },
                new RagChatService.ChatMetricContext(new AtomicReference<>("rag"),
                        new AtomicBoolean(false), System.nanoTime()));
    }

    private static LlmModel model(long id, String name) {
        LlmModel model = new LlmModel();
        model.setId(id);
        model.setName(name);
        model.setModelName(name);
        model.setProvider("OPENAI");
        return model;
    }

    private StreamingChatModel failingModelBeforeFirstToken(String message) {
        StreamingChatModel model = org.mockito.Mockito.mock(StreamingChatModel.class);
        doAnswer(inv -> {
            StreamingChatResponseHandler handler = inv.getArgument(1);
            handler.onError(new RuntimeException(message));
            return null;
        }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        return model;
    }

    private StreamingChatModel answeringModel(String answer) {
        StreamingChatModel model = org.mockito.Mockito.mock(StreamingChatModel.class);
        doAnswer(inv -> {
            StreamingChatResponseHandler handler = inv.getArgument(1);
            handler.onPartialResponse(answer);
            handler.onCompleteResponse(ChatResponse.builder()
                    .aiMessage(AiMessage.from(answer))
                    .build());
            return null;
        }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        return model;
    }

    /** 记录 SSE 事件名与 payload 的 SseEmitter，用于断言事件序列 */
    private static final class CapturingEmitter extends SseEmitter {
        record Event(String name, Object data) {
        }

        private final List<Event> events = Collections.synchronizedList(new ArrayList<>());
        private volatile boolean completed;
        private volatile Throwable completedWithError;

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            String name = "message";
            Object data = null;
            for (ResponseBodyEmitter.DataWithMediaType item : builder.build()) {
                Object value = item.getData();
                if (value instanceof CharSequence text) {
                    for (String line : text.toString().split("\n")) {
                        if (line.startsWith("event:")) {
                            name = line.substring(6).trim();
                        }
                    }
                } else if (value != null) {
                    data = value;
                }
            }
            events.add(new Event(name, data));
        }

        @Override
        public void complete() {
            completed = true;
        }

        @Override
        public void completeWithError(Throwable ex) {
            completed = true;
            completedWithError = ex;
        }

        List<Event> eventsOf(String name) {
            return events.stream().filter(e -> name.equals(e.name())).toList();
        }

        List<String> textOf(String name) {
            return eventsOf(name).stream()
                    .map(Event::data)
                    .filter(Map.class::isInstance)
                    .map(data -> String.valueOf(((Map<?, ?>) data).get("text")))
                    .toList();
        }
    }
}
