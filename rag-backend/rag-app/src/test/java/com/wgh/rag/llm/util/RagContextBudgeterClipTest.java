package com.wgh.rag.llm.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文本裁剪工具（RagContextBudgeter.clip）测试。
 */
@DisplayName("文本 Token 裁剪")
class RagContextBudgeterClipTest {

    @Test
    @DisplayName("未超限 - 原样返回")
    void clip_withinBudget_returnsSame() {
        String text = "简短内容";
        assertThat(RagContextBudgeter.clip(text, 100)).isEqualTo(text);
    }

    @Test
    @DisplayName("超限 - 优先在句界截断")
    void clip_prefersSentenceBoundary() {
        String text = "第一句内容。第二句内容。第三句内容。";

        String clipped = RagContextBudgeter.clip(text, 8);

        assertThat(clipped.length()).isLessThan(text.length());
        assertThat(clipped).endsWith("。");
        assertThat(TokenEstimator.estimate(clipped)).isLessThanOrEqualTo(8);
    }

    @Test
    @DisplayName("超限且无句界 - 硬切到预算内")
    void clip_hardCutWithoutBoundary() {
        String text = "a".repeat(100);

        String clipped = RagContextBudgeter.clip(text, 10);

        assertThat(clipped).hasSize(40);
        assertThat(TokenEstimator.estimate(clipped)).isLessThanOrEqualTo(10);
    }

    @Test
    @DisplayName("非法预算 - 返回空串")
    void clip_invalidBudget() {
        assertThat(RagContextBudgeter.clip("abc", 0)).isEmpty();
        assertThat(RagContextBudgeter.clip("abc", -1)).isEmpty();
        assertThat(RagContextBudgeter.clip(null, 10)).isEmpty();
    }
}
