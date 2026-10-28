package com.wgh.rag.chat.compression;

import com.wgh.rag.chat.mapper.ChatSessionMapper;
import com.wgh.rag.chat.service.ContextCompressionService;
import com.wgh.rag.config.property.RagProperties;
import com.wgh.rag.llm.entity.LlmModel;
import com.wgh.rag.llm.factory.ModelCacheManager;
import com.wgh.rag.llm.service.LlmModelService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * 压缩责任链顺序测试：无论注入顺序如何，链按 order() 升序执行
 * RecentKeepHandler → SummaryHandler → FactExtractHandler。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("压缩责任链顺序测试")
class CompressionChainOrderTest {

    @Mock
    private ModelCacheManager modelCacheManager;
    @Mock
    private LlmModelService llmModelService;
    @Mock
    private ChatSessionMapper chatSessionMapper;
    @Mock
    private ChatModel chatModel;

    @Test
    @DisplayName("三级 handler order 值递增：近期 < 中期 < 远期")
    void orderValues_increase() {
        assertThat(new RecentKeepHandler().order()).isLessThan(new SummaryHandler().order());
        assertThat(new SummaryHandler().order()).isLessThan(new FactExtractHandler().order());
    }

    @Test
    @DisplayName("乱序注入时仍按 order 升序执行责任链")
    void chain_executesInOrderRegardlessOfInjectionOrder() {
        RecentKeepHandler recent = spy(new RecentKeepHandler());
        SummaryHandler summary = spy(new SummaryHandler());
        FactExtractHandler fact = spy(new FactExtractHandler());

        ContextCompressionService service = new ContextCompressionService(
                new RagProperties(), modelCacheManager, llmModelService, chatSessionMapper,
                List.of(fact, summary, recent)); // 故意乱序注入

        // 12 轮 25 条消息，触发完整三级压缩
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from("你是一个 AI 助手"));
        for (int i = 0; i < 12; i++) {
            messages.add(UserMessage.from("用户问题 #" + i));
            messages.add(AiMessage.from("AI 回答 #" + i));
        }

        LlmModel model = new LlmModel();
        model.setId(1L);
        model.setModelName("test-model");
        when(llmModelService.findCompressionModel()).thenReturn(model);
        when(modelCacheManager.getChatModel(1L)).thenReturn(chatModel);
        when(chatModel.chat(anyString())).thenReturn("压缩内容");
        when(chatSessionMapper.selectById(1L)).thenReturn(null);

        List<ChatMessage> result = service.compressHistory(messages, 20, 1L);

        InOrder inOrder = inOrder(recent, summary, fact);
        inOrder.verify(recent).handle(any(CompressionContext.class));
        inOrder.verify(summary).handle(any(CompressionContext.class));
        inOrder.verify(fact).handle(any(CompressionContext.class));

        // 三级均生效：System + 远期事实 + 中期摘要 + 近期 4 轮原文
        assertThat(result).hasSize(11);
    }
}
