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
 * 中期摘要处理器测试：复用 / 无模型跳过 / LLM 生成 / 失败降级。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("中期摘要处理器测试")
class SummaryHandlerTest {

    @Mock
    private ChatModel chatModel;

    private final SummaryHandler handler = new SummaryHandler();

    @Test
    @DisplayName("中期层为空时不处理")
    void shouldHandle_falseWhenMidLayerEmpty() {
        assertThat(handler.shouldHandle(ctxWithRounds(4, 0, 0))).isFalse();
        assertThat(handler.shouldHandle(ctxWithRounds(4, 0, 2))).isTrue();
    }

    @Test
    @DisplayName("已保存摘要可复用时直接使用，不调 LLM")
    void handle_reusable_usesSaved() {
        CompressionContext ctx = ctxWithRounds(6, 0, 2);
        ctx.setMidReusable(true);
        ctx.setSavedMidSummary("已保存摘要");
        ctx.setChatModel(chatModel);

        handler.handle(ctx);

        assertThat(ctx.getMidSummary()).isEqualTo("已保存摘要");
        assertThat(ctx.isDegraded()).isFalse();
        verifyNoInteractions(chatModel);
    }

    @Test
    @DisplayName("无压缩模型时跳过摘要，不标记降级")
    void handle_noModel_skips() {
        CompressionContext ctx = ctxWithRounds(6, 0, 2);
        ctx.setChatModel(null);

        handler.handle(ctx);

        assertThat(ctx.getMidSummary()).isNull();
        assertThat(ctx.isDegraded()).isFalse();
    }

    @Test
    @DisplayName("LLM 生成摘要，prompt 显式保留优先级")
    void handle_llmSuccess_generatesSummary() {
        CompressionContext ctx = ctxWithRounds(6, 0, 2);
        ctx.setChatModel(chatModel);
        when(chatModel.chat(anyString())).thenReturn("生成的摘要");

        handler.handle(ctx);

        assertThat(ctx.getMidSummary()).isEqualTo("生成的摘要");
        assertThat(ctx.isDegraded()).isFalse();

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatModel).chat(promptCaptor.capture());
        assertThat(promptCaptor.getValue())
                .contains("进展摘要")
                .contains("保留优先级")
                .contains("用户明确的需求")
                .contains("已确认的业务规则")
                .contains("实体名称")
                .contains("精确数据")
                .contains("背景闲聊")
                .contains("问题0"); // 中期层对话内容渲染进 prompt
    }

    @Test
    @DisplayName("LLM 异常时标记降级，由编排方统一截断")
    void handle_llmThrows_marksDegraded() {
        CompressionContext ctx = ctxWithRounds(6, 0, 2);
        ctx.setChatModel(chatModel);
        when(chatModel.chat(anyString())).thenThrow(new RuntimeException("LLM 超时"));

        handler.handle(ctx);

        assertThat(ctx.isDegraded()).isTrue();
        assertThat(ctx.getMidSummary()).isNull();
    }
}
