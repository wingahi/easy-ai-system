package com.wgh.rag.chat.service.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.agent.config.AgentProperties;
import com.wgh.rag.agent.service.AgentOrchestrator;
import com.wgh.rag.chat.constant.ChatConstants;
import com.wgh.rag.chat.entity.ChatSession;
import com.wgh.rag.chat.entity.ChatWindow;
import com.wgh.rag.chat.evaluation.EvaluationScheme;
import com.wgh.rag.chat.evaluation.JudgeModelSelector;
import com.wgh.rag.chat.evaluation.LlmJudgeService;
import com.wgh.rag.chat.mapper.ChatMessageMapper;
import com.wgh.rag.chat.mapper.ChatSessionMapper;
import com.wgh.rag.chat.mapper.ChatWindowMapper;
import com.wgh.rag.chat.service.ChatCancellationStore;
import com.wgh.rag.chat.service.ChatImageService;
import com.wgh.rag.chat.service.ChatWorkflowService;
import com.wgh.rag.chat.service.PromptTemplateService;
import com.wgh.rag.chat.service.cost.ChatCostSettler;
import com.wgh.rag.chat.service.evaluation.ChatEvaluationService;
import com.wgh.rag.chat.service.prompt.ChatContextBudgetService;
import com.wgh.rag.chat.service.prompt.ContextCompressionService;
import com.wgh.rag.chat.service.session.ChatMessageStore;
import com.wgh.rag.chat.service.stream.ChatEventPublisher;
import com.wgh.rag.chat.service.stream.ChatStreamCancellation;
import com.wgh.rag.config.property.RagProperties;
import com.wgh.rag.execution.event.ExecutionStreamSink;
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

    private ChatAnswerStreamer streamer;
    private ChatSession session;
    private ChatWindow window;

    @BeforeEach
    void setUp() {
        ChatMessageStore messageStore = new ChatMessageStore(messageMapper, sessionMapper, objectMapper,
                org.mockito.Mockito.mock(com.wgh.rag.kb.service.AgentMemoryService.class));
        ChatCostSettler costSettler = new ChatCostSettler(modelRouter, modelPriceService, quotaService,
                messageMapper, metrics);
        ChatEvaluationService evaluationService = new ChatEvaluationService(llmJudgeService, judgeModelSelector,
                objectMapper, messageMapper);
        streamer = new ChatAnswerStreamer(
                modelCacheManager,
                org.mockito.Mockito.mock(com.wgh.rag.tool.fallback.ToolInvoker.class),
                messageStore,
                costSettler,
                evaluationService,
                org.mockito.Mockito.mock(ChatContextBudgetService.class),
                semanticCacheService,
                metrics);
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

        streamer.start(context(List.of(primary, backup), emitter, actualModel, switched), 0);

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

        streamer.start(context(List.of(primary, backup), emitter, actualModel, switched), 0);

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

        streamer.start(context(List.of(primary, backup), emitter, actualModel, switched), 0);

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

        streamer.start(context(List.of(primary, backup), emitter, actualModel, switched), 0);

        assertThat(switched).containsExactly(backup);
        assertThat(emitter.eventsOf("done")).hasSize(1);
        assertThat(emitter.textOf("content")).containsExactly("答案");
    }

    @Test
    void clientDisconnectDuringStream_stopsConsumingAndPersistsStopped() {
        LlmModel primary = model(1L, "primary");
        StreamingChatModel model = org.mockito.Mockito.mock(StreamingChatModel.class);
        doAnswer(inv -> {
            StreamingChatResponseHandler handler = inv.getArgument(1);
            handler.onPartialResponse("第一段");
            handler.onPartialResponse("第二段");
            handler.onPartialResponse("第三段");
            handler.onCompleteResponse(ChatResponse.builder()
                    .aiMessage(AiMessage.from("第一段第二段第三段"))
                    .build());
            return null;
        }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        when(modelCacheManager.getStreamingChatModel(1L)).thenReturn(model);

        FailAfterFirstContentEmitter emitter = new FailAfterFirstContentEmitter();
        AtomicReference<LlmModel> actualModel = new AtomicReference<>();
        List<LlmModel> switched = new ArrayList<>();
        ChatStreamCancellation cancellation = new ChatStreamCancellation();

        streamer.start(context(List.of(primary), emitter, actualModel, switched, cancellation), 0);

        assertThat(cancellation.isCancelled()).isTrue();
        assertThat(emitter.textOf("content")).containsExactly("第一段");
        assertThat(emitter.eventsOf("done")).isEmpty();
        assertThat(emitter.eventsOf("error")).isEmpty();
        com.wgh.rag.chat.entity.ChatMessage saved = captureInsertedMessage();
        assertThat(saved.getStatus()).isEqualTo(ChatConstants.MESSAGE_STATUS_STOPPED);
        // 第二个 token 已追加但写失败，后续 token（第三段）不再消费
        assertThat(saved.getContent()).isEqualTo("第一段第二段");
        verify(quotaService).addTokens(org.mockito.ArgumentMatchers.eq("tester"),
                org.mockito.ArgumentMatchers.longThat(tokens -> tokens > 0));
    }

    @Test
    void explicitCancel_stopsFurtherTokensAndPersistsStopped() {
        LlmModel primary = model(1L, "primary");
        StreamingChatModel model = org.mockito.Mockito.mock(StreamingChatModel.class);
        ChatStreamCancellation cancellation = new ChatStreamCancellation();
        doAnswer(inv -> {
            StreamingChatResponseHandler handler = inv.getArgument(1);
            handler.onPartialResponse("A");
            // 显式取消：等价于外部调用取消句柄
            cancellation.cancel();
            handler.onPartialResponse("B");
            handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("AB")).build());
            return null;
        }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        when(modelCacheManager.getStreamingChatModel(1L)).thenReturn(model);

        CapturingEmitter emitter = new CapturingEmitter();
        AtomicReference<LlmModel> actualModel = new AtomicReference<>();
        List<LlmModel> switched = new ArrayList<>();

        streamer.start(context(List.of(primary), emitter, actualModel, switched, cancellation), 0);

        assertThat(emitter.textOf("content")).containsExactly("A");
        assertThat(emitter.eventsOf("done")).isEmpty();
        com.wgh.rag.chat.entity.ChatMessage saved = captureInsertedMessage();
        assertThat(saved.getStatus()).isEqualTo(ChatConstants.MESSAGE_STATUS_STOPPED);
        assertThat(saved.getContent()).isEqualTo("A");
        verify(quotaService).addTokens(org.mockito.ArgumentMatchers.eq("tester"),
                org.mockito.ArgumentMatchers.longThat(tokens -> tokens > 0));
    }

    @Test
    void normalCompletion_persistsCompletedStatusAndCountsTokens() {
        LlmModel primary = model(1L, "primary");
        StreamingChatModel model = answeringModel("答案");
        when(modelCacheManager.getStreamingChatModel(1L)).thenReturn(model);

        CapturingEmitter emitter = new CapturingEmitter();
        AtomicReference<LlmModel> actualModel = new AtomicReference<>();
        List<LlmModel> switched = new ArrayList<>();

        streamer.start(context(List.of(primary), emitter, actualModel, switched), 0);

        assertThat(emitter.eventsOf("done")).hasSize(1);
        com.wgh.rag.chat.entity.ChatMessage saved = captureInsertedMessage();
        assertThat(saved.getStatus()).isEqualTo(ChatConstants.MESSAGE_STATUS_COMPLETED);
        verify(quotaService).addTokens(org.mockito.ArgumentMatchers.eq("tester"),
                org.mockito.ArgumentMatchers.longThat(tokens -> tokens > 0));
    }

    private com.wgh.rag.chat.entity.ChatMessage captureInsertedMessage() {
        org.mockito.ArgumentCaptor<com.wgh.rag.chat.entity.ChatMessage> captor =
                org.mockito.ArgumentCaptor.forClass(com.wgh.rag.chat.entity.ChatMessage.class);
        verify(messageMapper).insert(captor.capture());
        return captor.getValue();
    }

    // ==================== 辅助方法 ====================

    private ChatTurnContext context(List<LlmModel> chain, CapturingEmitter emitter,
                                    AtomicReference<LlmModel> actualModel,
                                    List<LlmModel> switched) {
        return context(chain, emitter, actualModel, switched, new ChatStreamCancellation());
    }

    private ChatTurnContext context(List<LlmModel> chain, CapturingEmitter emitter,
                                    AtomicReference<LlmModel> actualModel,
                                    List<LlmModel> switched,
                                    ChatStreamCancellation cancellation) {
        ChatTurnContext ctx = ChatTurnContext.builder()
                .window(window)
                .session(session)
                .userMessage("hi")
                .events(new ChatEventPublisher(emitter))
                .shareMode(false)
                .quotaUsername("tester")
                .imageUrls(List.of())
                .metricCtx(new ChatMetricContext(new AtomicReference<>("rag"),
                        new AtomicBoolean(false), System.nanoTime()))
                .cancellation(cancellation)
                .build();
        ctx.kbIds = List.of();
        ctx.modelChain = chain;
        ctx.request = ChatRequest.builder()
                .messages(new ArrayList<>(List.of(UserMessage.from("hi")))).build();
        ctx.toolSpecs = List.of();
        ctx.messages = new ArrayList<>(List.of(UserMessage.from("hi")));
        ctx.retrievedMatches = List.of();
        ctx.evaluationScheme = EvaluationScheme.NONE;
        ctx.maxToolRounds = 5;
        ctx.toolRegistry = Map.of();
        ctx.actualModel = actualModel;
        ctx.onModelSwitch = (m, reason) -> {
            switched.add(m);
            actualModel.set(m);
        };
        ctx.toolBudget = new com.wgh.rag.tool.fallback.ToolTaskBudget(50, 120_000);
        return ctx;
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

    /** 记录聊天事件名与 payload 的事件出口，用于断言事件序列 */
    private static class CapturingEmitter implements ExecutionStreamSink {
        record Event(String name, Object data) {
        }

        final List<Event> events = Collections.synchronizedList(new ArrayList<>());
        private volatile boolean completed;
        private volatile Throwable completedWithError;

        @Override
        public void emit(String type, Object payload) throws IOException {
            record(new Event(type, payload));
        }

        void record(Event event) {
            events.add(event);
        }

        @Override
        public void complete() {
            completed = true;
        }

        @Override
        public void fail(Throwable ex) {
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

    /** 第二个 content 事件写失败，模拟客户端在流式过程中断开 */
    private static final class FailAfterFirstContentEmitter extends CapturingEmitter {
        private final java.util.concurrent.atomic.AtomicInteger contentEmitCount =
                new java.util.concurrent.atomic.AtomicInteger();

        @Override
        public void emit(String type, Object payload) throws IOException {
            if ("content".equals(type) && contentEmitCount.incrementAndGet() > 1) {
                throw new IOException("client disconnected");
            }
            record(new Event(type, payload));
        }
    }
}
