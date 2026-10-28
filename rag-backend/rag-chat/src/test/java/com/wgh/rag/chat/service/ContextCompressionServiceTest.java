package com.wgh.rag.chat.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.wgh.rag.chat.entity.ChatSession;
import com.wgh.rag.chat.mapper.ChatSessionMapper;
import com.wgh.rag.config.property.RagProperties;
import com.wgh.rag.llm.entity.LlmModel;
import com.wgh.rag.llm.factory.ModelCacheManager;
import com.wgh.rag.llm.service.LlmModelService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ContextCompressionService 当前行为单元测试（重构兜底）
 *
 * <p>覆盖行为不变式：
 * ≤阈值不触发 / >阈值触发三级压缩 / 无压缩模型降级 / LLM 异常与超时降级 /
 * 压缩结果复用 / prompt 版本不匹配重新生成 / 摘要 prompt 保留优先级。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("上下文压缩服务行为测试")
class ContextCompressionServiceTest {

    private static final Long SESSION_ID = 100L;

    @Mock
    private ModelCacheManager modelCacheManager;
    @Mock
    private LlmModelService llmModelService;
    @Mock
    private ChatSessionMapper chatSessionMapper;
    @Mock
    private ChatModel chatModel;

    private RagProperties ragProperties;
    private ContextCompressionService service;

    @BeforeEach
    void setUp() {
        ragProperties = new RagProperties();
        service = new ContextCompressionService(ragProperties, modelCacheManager,
                llmModelService, chatSessionMapper);
    }

    // ==================== 触发条件 ====================

    @Test
    @DisplayName("消息数 ≤ maxHistory 时不触发压缩，原样返回")
    void withinLimit_returnsAsIs() {
        List<ChatMessage> messages = buildMessages(3); // 1 System + 6 对话 = 7 条
        List<ChatMessage> result = service.compressHistory(messages, 20, SESSION_ID);
        assertThat(result).isSameAs(messages);
        verifyNoInteractions(llmModelService, modelCacheManager, chatSessionMapper);
    }

    @Test
    @DisplayName("对话轮次 ≤ 近期窗口时仅截断不压缩")
    void roundsWithinRecentWindow_fallbackTruncate() {
        // 4 轮 = 9 条消息 > maxHistory=5，但轮次 4 ≤ 近期窗口 4
        List<ChatMessage> messages = buildMessages(4);
        List<ChatMessage> result = service.compressHistory(messages, 5, SESSION_ID);

        // 简单截断：System + 最近 4 条非 System 消息
        assertThat(result).hasSize(5);
        assertThat(result.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(result.subList(1, 5)).containsExactlyElementsOf(messages.subList(5, 9));
        verifyNoInteractions(llmModelService, modelCacheManager);
    }

    // ==================== 正常三级压缩 ====================

    @Test
    @DisplayName(">20 条触发三级压缩：远期事实 + 中期摘要 + 近期原文，并持久化")
    void exceedLimit_fullLayeredCompression() {
        List<ChatMessage> messages = buildMessages(12); // 25 条，12 轮：远期 2 + 中期 6 + 近期 4
        stubModelAvailable();
        when(chatSessionMapper.selectById(SESSION_ID)).thenReturn(null);

        List<ChatMessage> result = service.compressHistory(messages, 20, SESSION_ID);

        // System + 远期事实 + 中期摘要 + 近期 4 轮原文（8 条）= 11 条
        assertThat(result).hasSize(11);
        assertThat(result.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(((SystemMessage) result.get(1)).text())
                .contains("【远期对话关键事实】").contains("远期事实内容");
        assertThat(((SystemMessage) result.get(2)).text())
                .contains("【中期对话进展摘要】").contains("中期摘要内容");
        // 近期原文保持原消息引用与顺序（第 8~11 轮）
        assertThat(result.subList(3, 11)).containsExactlyElementsOf(messages.subList(17, 25));

        // LLM 被调用两次（中期摘要 + 远期事实）
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatModel, times(2)).chat(promptCaptor.capture());
        List<String> prompts = promptCaptor.getAllValues();
        // 摘要 prompt 显式保留优先级：需求/业务规则/实体名称/精确数据优先，删背景闲聊
        assertThat(prompts.get(0))
                .contains("保留优先级")
                .contains("用户明确的需求")
                .contains("实体名称")
                .contains("精确数据")
                .contains("背景闲聊")
                .contains("用户: ");
        assertThat(prompts.get(1))
                .contains("保留优先级")
                .contains("只提取关键事实")
                .contains("每行一个事实点");

        // 压缩结果持久化到 session
        verify(chatSessionMapper).update(isNull(), any(UpdateWrapper.class));
    }

    @Test
    @DisplayName("仅中期层非空时只生成摘要，不提取远期事实")
    void onlyMidTerm_generatesSummaryOnly() {
        // 8 轮：近期 4 + 中期 4，远期 0；17 条 > maxHistory=10
        List<ChatMessage> messages = buildMessages(8);
        stubModelAvailable();
        when(chatSessionMapper.selectById(SESSION_ID)).thenReturn(null);

        List<ChatMessage> result = service.compressHistory(messages, 10, SESSION_ID);

        // System + 中期摘要 + 近期 4 轮 = 10 条，无远期事实
        assertThat(result).hasSize(10);
        assertThat(((SystemMessage) result.get(1)).text()).contains("【中期对话进展摘要】");
        verify(chatModel, times(1)).chat(anyString());
    }

    // ==================== 降级策略 ====================

    @Test
    @DisplayName("无压缩模型配置时降级：跳过 LLM 摘要，保留 System + 近期原文")
    void noCompressionModel_degrades() {
        List<ChatMessage> messages = buildMessages(12);
        when(llmModelService.findCompressionModel()).thenReturn(null);
        when(llmModelService.listEnabledChatModels()).thenReturn(Collections.emptyList());
        when(chatSessionMapper.selectById(SESSION_ID)).thenReturn(null);

        List<ChatMessage> result = service.compressHistory(messages, 20, SESSION_ID);

        // System + 近期 4 轮原文 = 9 条，不抛异常、不阻塞主流程
        assertThat(result).hasSize(9);
        assertThat(result.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(result.subList(1, 9)).containsExactlyElementsOf(messages.subList(17, 25));
        verifyNoInteractions(chatModel);
        // 无可保存内容，不执行 update
        verify(chatSessionMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("LLM API 异常时降级为简单截断")
    void llmException_fallbackTruncate() {
        List<ChatMessage> messages = buildMessages(12);
        stubModelAvailable();
        when(chatModel.chat(anyString())).thenThrow(new RuntimeException("LLM API 不可用"));
        when(chatSessionMapper.selectById(SESSION_ID)).thenReturn(null);

        List<ChatMessage> result = service.compressHistory(messages, 20, SESSION_ID);

        assertFallbackTruncate(result, messages, 20);
        verify(chatSessionMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("LLM 调用超时时降级为简单截断")
    void llmTimeout_fallbackTruncate() {
        ragProperties.getContext().setSummaryTimeoutSeconds(1);
        List<ChatMessage> messages = buildMessages(12);
        stubModelAvailable();
        when(chatModel.chat(anyString())).thenAnswer(inv -> {
            Thread.sleep(3000);
            return "迟到的摘要";
        });
        when(chatSessionMapper.selectById(SESSION_ID)).thenReturn(null);

        List<ChatMessage> result = service.compressHistory(messages, 20, SESSION_ID);

        assertFallbackTruncate(result, messages, 20);
    }

    // ==================== 复用逻辑 ====================

    @Test
    @DisplayName("范围与 prompt 版本匹配时复用已保存压缩结果，不调 LLM")
    void reusableCompression_skipsLlm() {
        List<ChatMessage> messages = buildMessages(12); // midStart=2, recentStart=8
        ChatSession saved = new ChatSession();
        saved.setMidSummary("已保存的中期摘要");
        saved.setMidSummaryRoundStart(2);
        saved.setMidSummaryRoundEnd(8);
        saved.setMidSummaryPromptVersion("v1");
        saved.setLongTermFacts("已保存的远期事实");
        saved.setLongTermFactsRoundEnd(2);
        saved.setLongTermFactsPromptVersion("v1");
        when(chatSessionMapper.selectById(SESSION_ID)).thenReturn(saved);

        List<ChatMessage> result = service.compressHistory(messages, 20, SESSION_ID);

        assertThat(result).hasSize(11);
        assertThat(((SystemMessage) result.get(1)).text()).contains("已保存的远期事实");
        assertThat(((SystemMessage) result.get(2)).text()).contains("已保存的中期摘要");
        // 全部复用：不解析模型、不调 LLM、不重复保存
        verifyNoInteractions(llmModelService, modelCacheManager, chatModel);
        verify(chatSessionMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("prompt 版本不匹配时重新生成压缩结果")
    void promptVersionMismatch_regenerates() {
        List<ChatMessage> messages = buildMessages(12);
        ChatSession saved = new ChatSession();
        saved.setMidSummary("旧版摘要");
        saved.setMidSummaryRoundStart(2);
        saved.setMidSummaryRoundEnd(8);
        saved.setMidSummaryPromptVersion("v0"); // 旧版本
        saved.setLongTermFacts("旧版事实");
        saved.setLongTermFactsRoundEnd(2);
        saved.setLongTermFactsPromptVersion("v0");
        when(chatSessionMapper.selectById(SESSION_ID)).thenReturn(saved);
        stubModelAvailable();

        List<ChatMessage> result = service.compressHistory(messages, 20, SESSION_ID);

        // 重新生成，不复用旧版本内容
        assertThat(((SystemMessage) result.get(1)).text()).contains("远期事实内容");
        assertThat(((SystemMessage) result.get(2)).text()).contains("中期摘要内容");
        verify(chatModel, times(2)).chat(anyString());
        verify(chatSessionMapper).update(isNull(), any(UpdateWrapper.class));
    }

    // ==================== 辅助方法 ====================

    /** 构建 1 条 System + turnCount 轮 user/ai 对话，共 1 + 2*turnCount 条 */
    private List<ChatMessage> buildMessages(int turnCount) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from("你是一个 AI 助手"));
        for (int i = 0; i < turnCount; i++) {
            messages.add(UserMessage.from("用户问题 #" + i));
            messages.add(AiMessage.from("AI 回答 #" + i));
        }
        return messages;
    }

    /** 配置压缩模型可用，LLM 按 prompt 类型返回固定内容 */
    private void stubModelAvailable() {
        LlmModel model = new LlmModel();
        model.setId(1L);
        model.setModelName("test-compression-model");
        when(llmModelService.findCompressionModel()).thenReturn(model);
        when(modelCacheManager.getChatModel(1L)).thenReturn(chatModel);
        when(chatModel.chat(anyString())).thenAnswer(inv -> {
            String prompt = inv.getArgument(0);
            return prompt.contains("进展摘要") ? "中期摘要内容" : "远期事实内容";
        });
    }

    /** 断言结果为简单截断：System + 最近 maxHistory-1 条非 System 消息 */
    private void assertFallbackTruncate(List<ChatMessage> result, List<ChatMessage> original, int maxHistory) {
        assertThat(result).hasSize(maxHistory);
        assertThat(result.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(result.subList(1, maxHistory))
                .containsExactlyElementsOf(original.subList(original.size() - maxHistory + 1, original.size()));
    }
}
