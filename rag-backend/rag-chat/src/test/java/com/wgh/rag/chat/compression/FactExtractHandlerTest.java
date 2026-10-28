package com.wgh.rag.chat.compression;

import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.wgh.rag.chat.compression.RecentKeepHandlerTest.ctxWithRounds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 远期事实提取处理器测试：复用 / 无模型跳过 / LLM 提取 / 失败降级。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("远期事实提取处理器测试")
class FactExtractHandlerTest {

    @Mock
    private ChatModel chatModel;

    private final FactExtractHandler handler = new FactExtractHandler();

    /** 远期层为空返回 false，远期层有内容返回 true */
    @Test
    @DisplayName("远期层为空时不处理")
    void shouldHandle_falseWhenLongTermLayerEmpty() {
        assertThat(handler.shouldHandle(ctxWithRounds(4, 0, 2))).isFalse();
        assertThat(handler.shouldHandle(ctxWithRounds(4, 1, 2))).isTrue();
    }

    /** 事实可复用时直接采用已保存内容，且不触发 LLM 调用 */
    @Test
    @DisplayName("已保存事实可复用时直接使用，不调 LLM")
    void handle_reusable_usesSaved() {
        CompressionContext ctx = ctxWithRounds(6, 1, 3);
        ctx.setLongTermReusable(true);
        ctx.setSavedLongTermFacts("已保存事实");
        ctx.setChatModel(chatModel);

        handler.handle(ctx);

        assertThat(ctx.getLongTermFacts()).isEqualTo("已保存事实");
        assertThat(ctx.isDegraded()).isFalse();
        verifyNoInteractions(chatModel);
    }

    /** 缺少压缩模型时跳过事实提取，且不标记为降级 */
    @Test
    @DisplayName("无压缩模型时跳过提取，不标记降级")
    void handle_noModel_skips() {
        CompressionContext ctx = ctxWithRounds(6, 1, 3);
        ctx.setChatModel(null);

        handler.handle(ctx);

        assertThat(ctx.getLongTermFacts()).isNull();
        assertThat(ctx.isDegraded()).isFalse();
    }

    /** LLM 正常返回时写入事实，且 prompt 包含关键事实提取要求与远期层内容 */
    @Test
    @DisplayName("LLM 提取事实，prompt 只留关键事实优先级")
    void handle_llmSuccess_extractsFacts() {
        CompressionContext ctx = ctxWithRounds(6, 1, 3);
        ctx.setChatModel(chatModel);
        when(chatModel.chat(anyString())).thenReturn("- 事实1");

        handler.handle(ctx);

        assertThat(ctx.getLongTermFacts()).isEqualTo("- 事实1");
        assertThat(ctx.isDegraded()).isFalse();

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatModel).chat(promptCaptor.capture());
        assertThat(promptCaptor.getValue())
                .contains("只提取关键事实点")
                .contains("保留优先级")
                .contains("用户明确的需求")
                .contains("实体名称")
                .contains("精确数据")
                .contains("每行一个事实点")
                .contains("问题0"); // 远期层对话内容渲染进 prompt
    }

    /** LLM 抛异常时标记降级并保持事实为空，交由编排方截断 */
    @Test
    @DisplayName("LLM 异常时标记降级，由编排方统一截断")
    void handle_llmThrows_marksDegraded() {
        CompressionContext ctx = ctxWithRounds(6, 1, 3);
        ctx.setChatModel(chatModel);
        when(chatModel.chat(anyString())).thenThrow(new RuntimeException("LLM 超时"));

        handler.handle(ctx);

        assertThat(ctx.isDegraded()).isTrue();
        assertThat(ctx.getLongTermFacts()).isNull();
    }
}
