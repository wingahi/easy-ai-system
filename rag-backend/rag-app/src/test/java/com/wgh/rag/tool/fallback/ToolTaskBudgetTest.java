package com.wgh.rag.tool.fallback;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 任务级工具预算测试（请求数 + 总时长）。
 */
@DisplayName("工具任务预算")
class ToolTaskBudgetTest {

    @Test
    @DisplayName("请求数上限")
    void requestLimit() {
        ToolTaskBudget budget = new ToolTaskBudget(2, 1_000, () -> 0L);
        assertThat(budget.tryAcquire()).isTrue();
        assertThat(budget.tryAcquire()).isTrue();
        assertThat(budget.tryAcquire()).isFalse();
        assertThat(budget.used()).isEqualTo(2);
    }

    @Test
    @DisplayName("总时长上限")
    void timeLimit() {
        AtomicLong now = new AtomicLong(0L);
        ToolTaskBudget budget = new ToolTaskBudget(10, 1_000, now::get);
        assertThat(budget.tryAcquire()).isTrue();

        now.addAndGet(1_000L);
        assertThat(budget.remainingMs()).isZero();
        assertThat(budget.tryAcquire()).isFalse();
    }

    @Test
    @DisplayName("默认配置 - 50 次 / 120s")
    void fromProperties() {
        ToolFallbackProperties properties = new ToolFallbackProperties();
        ToolTaskBudget budget = ToolTaskBudget.of(properties);
        assertThat(budget.remainingMs()).isGreaterThan(0);
        assertThat(budget.used()).isZero();
    }
}
