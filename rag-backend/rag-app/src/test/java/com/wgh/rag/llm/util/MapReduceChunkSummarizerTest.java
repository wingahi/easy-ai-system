package com.wgh.rag.llm.util;

import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * map-reduce 分段摘要测试。
 */
@DisplayName("map-reduce 分段摘要")
class MapReduceChunkSummarizerTest {

    @Test
    @DisplayName("触发条件 - 仅当单条 chunk 超过预算")
    void shouldApply() {
        assertThat(MapReduceChunkSummarizer.shouldApply(6000, 4000)).isTrue();
        assertThat(MapReduceChunkSummarizer.shouldApply(4000, 4000)).isFalse();
        assertThat(MapReduceChunkSummarizer.shouldApply(0, 4000)).isFalse();
        assertThat(MapReduceChunkSummarizer.shouldApply(6000, 0)).isFalse();
    }

    @Test
    @DisplayName("分段 - 按句界切分且不丢内容")
    void splitKeepsContent() {
        String text = "第一句。第二句！第三句？第四句。";

        List<String> parts = MapReduceChunkSummarizer.split(text, 2);

        assertThat(parts).hasSize(2);
        assertThat(String.join("", parts)).isEqualTo(text);
    }

    @Test
    @DisplayName("分段 - 英文小数点不被误判为句末")
    void splitDoesNotBreakDecimal() {
        String text = "阈值是 0.55。另一个值是 1.20。";

        List<String> sentences = MapReduceChunkSummarizer.splitSentences(text);

        assertThat(sentences).hasSize(2);
        assertThat(sentences.get(0)).contains("0.55");
    }

    @Test
    @DisplayName("分段 - 单句不触发摘要")
    void singleSentenceNotReduced() {
        ChatModel model = Mockito.mock(ChatModel.class);

        MapReduceChunkSummarizer.Result result = MapReduceChunkSummarizer.summarize(
                "只有一句话。", model, 4, 200, 1000);

        assertThat(result.reduced()).isFalse();
        verify(model, never()).chat(anyString());
    }

    @Test
    @DisplayName("摘要 - 每段调用一次并合并为带序号的要点")
    void summarizeMergesSegments() {
        ChatModel model = Mockito.mock(ChatModel.class);
        when(model.chat(anyString())).thenAnswer(inv -> {
            String prompt = inv.getArgument(0);
            return prompt.contains("第一句") ? "要点一" : "要点二";
        });

        MapReduceChunkSummarizer.Result result = MapReduceChunkSummarizer.summarize(
                "第一句。第二句。第三句。第四句。", model, 2, 200, 1000);

        assertThat(result.reduced()).isTrue();
        assertThat(result.segments()).isEqualTo(2);
        assertThat(result.text()).contains("【分段摘要").contains("1. 要点一").contains("2. 要点二");
        verify(model, times(2)).chat(anyString());
    }

    @Test
    @DisplayName("摘要 - 全部调用失败时降级为原文")
    void summarizeFallsBackWhenAllFail() {
        ChatModel model = Mockito.mock(ChatModel.class);
        when(model.chat(anyString())).thenThrow(new RuntimeException("model down"));
        String text = "第一句。第二句。第三句。";

        MapReduceChunkSummarizer.Result result = MapReduceChunkSummarizer.summarize(text, model, 3, 200, 1000);

        assertThat(result.reduced()).isFalse();
        assertThat(result.text()).isEqualTo(text);
    }

    @Test
    @DisplayName("摘要 - 合并结果受 Token 预算裁剪")
    void summarizeRespectsBudget() {
        ChatModel model = Mockito.mock(ChatModel.class);
        when(model.chat(anyString())).thenReturn("要".repeat(500));

        MapReduceChunkSummarizer.Result result = MapReduceChunkSummarizer.summarize(
                "第一句。第二句。第三句。", model, 3, 200, 100);

        assertThat(result.reduced()).isTrue();
        assertThat(TokenEstimator.estimate(result.text())).isLessThanOrEqualTo(100);
    }
}
