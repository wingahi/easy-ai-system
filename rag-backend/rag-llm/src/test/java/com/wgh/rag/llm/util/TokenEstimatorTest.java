package com.wgh.rag.llm.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TokenEstimator 启发式估算测试
 */
class TokenEstimatorTest {

    @Test
    @DisplayName("空文本估算为 0")
    void emptyText() {
        assertThat(TokenEstimator.estimate(null)).isZero();
        assertThat(TokenEstimator.estimate("")).isZero();
    }

    @Test
    @DisplayName("纯中文：1 字 ≈ 1 token")
    void pureCjk() {
        assertThat(TokenEstimator.estimate("你好世界")).isEqualTo(4);
    }

    @Test
    @DisplayName("纯英文：4 字符 ≈ 1 token（向上取整）")
    void pureEnglish() {
        assertThat(TokenEstimator.estimate("abcd")).isEqualTo(1);
        assertThat(TokenEstimator.estimate("abcde")).isEqualTo(2);
    }

    @Test
    @DisplayName("中英混合分别累计")
    void mixed() {
        // "你好" = 2，"abcd" = 1 → 3
        assertThat(TokenEstimator.estimate("你好abcd")).isEqualTo(3);
    }
}
