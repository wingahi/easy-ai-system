package com.wgh.rag.llm.util;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检索上下文 Token 预算器测试
 */
class RagContextBudgeterTest {

    @Test
    void trim_withinBudget_keepsAllAndSortsByScore() {
        List<EmbeddingMatch<TextSegment>> matches = List.of(
                match(0.6, "低分内容"),
                match(0.9, "高分内容"),
                match(0.75, "中分内容"));

        RagContextBudgeter.Result result = RagContextBudgeter.trim(matches, 1000, 120);

        assertThat(result.keptCount()).isEqualTo(3);
        assertThat(result.dropped()).isEmpty();
        assertThat(result.truncated()).isFalse();
        assertThat(result.kept()).extracting(m -> m.score()).containsExactly(0.9, 0.75, 0.6);
    }

    @Test
    void trim_overBudget_dropsLowestScoreFirstAndReportsDropped() {
        String hundredChars = "内".repeat(100); // CJK 1 字 ≈ 1 token
        List<EmbeddingMatch<TextSegment>> matches = List.of(
                matchWithFile(0.7, "c.pdf", 3, hundredChars),
                matchWithFile(0.9, "a.pdf", 1, hundredChars),
                matchWithFile(0.8, "b.pdf", 2, hundredChars));

        RagContextBudgeter.Result result = RagContextBudgeter.trim(matches, 230, 120);

        assertThat(result.keptCount()).isEqualTo(2);
        assertThat(result.kept()).extracting(m -> m.embedded().metadata().getString("fileName"))
                .containsExactly("a.pdf", "b.pdf");
        assertThat(result.dropped()).hasSize(1);
        assertThat(result.dropped().get(0).fileName()).isEqualTo("c.pdf");
        assertThat(result.dropped().get(0).chunkIndex()).isEqualTo(3);
        assertThat(result.keptTokens()).isLessThanOrEqualTo(result.budgetTokens());
    }

    @Test
    void trim_singleOversizedChunk_truncatesInsteadOfDroppingEverything() {
        List<EmbeddingMatch<TextSegment>> matches = List.of(
                match(0.9, "很长的内容。".repeat(120)));

        RagContextBudgeter.Result result = RagContextBudgeter.trim(matches, 120, 120);

        assertThat(result.keptCount()).isEqualTo(1);
        assertThat(result.truncated()).isTrue();
        assertThat(result.singleChunkSaturated()).isTrue();
        assertThat(result.kept().get(0).embedded().text()).contains("已截断");
        assertThat(result.keptTokens()).isLessThanOrEqualTo(result.budgetTokens());
    }

    @Test
    void trim_emptyInput_returnsEmptyResult() {
        RagContextBudgeter.Result result = RagContextBudgeter.trim(List.of(), 500, 120);

        assertThat(result.kept()).isEmpty();
        assertThat(result.originalCount()).isZero();
        assertThat(result.keptTokens()).isZero();
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void computeBudget_subtractsPromptTokensFromWindowShare() {
        // 8192 × 0.6 = 4915，再减去已占用 prompt 500
        int budget = RagContextBudgeter.computeBudget(8192, 500, 6000, 0.6, 120);

        assertThat(budget).isEqualTo(4415);
    }

    @Test
    void computeBudget_capsAtAbsoluteLimitForLargeWindow() {
        // 32000 × 0.6 = 19200，被硬上限 6000 收敛
        assertThat(RagContextBudgeter.computeBudget(32000, 100, 6000, 0.6, 120))
                .isEqualTo(6000);
    }

    @Test
    void computeBudget_fallsBackWhenModelWindowMissing() {
        int noWindow = RagContextBudgeter.computeBudget(null, 200, 6000, 0.6, 120);
        int zeroWindow = RagContextBudgeter.computeBudget(0, 200, 6000, 0.6, 120);

        // 8192 × 0.6 = 4915 - 200
        assertThat(noWindow).isEqualTo(4715);
        assertThat(zeroWindow).isEqualTo(4715);
    }

    @Test
    void computeBudget_neverBelowMinChunkTokens() {
        // prompt 已占满窗口：仍然保留最小预算，避免上下文整体丢失
        assertThat(RagContextBudgeter.computeBudget(4096, 4000, 6000, 0.6, 120))
                .isEqualTo(120);
    }

    private EmbeddingMatch<TextSegment> match(double score, String text) {
        return new EmbeddingMatch<>(score, "id-" + text, null, TextSegment.from(text));
    }

    private EmbeddingMatch<TextSegment> matchWithFile(double score, String fileName, int chunkIndex, String text) {
        dev.langchain4j.data.document.Metadata metadata = new dev.langchain4j.data.document.Metadata();
        metadata.put("fileName", fileName);
        metadata.put("chunkIndex", chunkIndex);
        return new EmbeddingMatch<>(score, "id-" + fileName, null, TextSegment.from(text, metadata));
    }
}
