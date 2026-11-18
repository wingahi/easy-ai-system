package com.wgh.rag.context;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 统一上下文预算分配器测试。
 */
@DisplayName("ContextAssembler 预算分配")
class ContextAssemblerTest {

    /** 4 字符 ≈ 1 token 的简化估算 */
    private static final ContextAssembler.TokenCounter COUNTER = text -> text == null ? 0 : (text.length() + 3) / 4;
    /** 按 token 硬切，模拟调用方注入的句界裁剪 */
    private static final ContextAssembler.Trimmer TRIMMER =
            (text, maxTokens) -> text.substring(0, Math.min(text.length(), maxTokens * 4));

    private static String text(int tokens) {
        return "a".repeat(tokens * 4);
    }

    @Test
    @DisplayName("按优先级分配 - 高优先级完整保留，低优先级裁剪")
    void keepsByPriorityAndTrims() {
        List<ContextSegment> segments = List.of(
                new ContextSegment(0, SegmentType.RETRIEVAL, text(60)),
                new ContextSegment(1, SegmentType.SYSTEM, text(10), false),
                new ContextSegment(2, SegmentType.CURRENT_QUESTION, text(10), false));

        ContextAssembler.Result result = ContextAssembler.assemble(
                100, ContextAssembler.Options.of(0.5, 0, 0), segments, COUNTER, TRIMMER);

        assertThat(result.budgetTokens()).isEqualTo(50);
        assertThat(result.kept()).hasSize(3);
        // kept 按入参 index 升序：0=检索（被裁剪），1=system，2=当前问题
        assertThat(result.kept().get(0).type()).isEqualTo(SegmentType.RETRIEVAL);
        assertThat(result.kept().get(0).trimmed()).isTrue();
        assertThat(result.kept().get(0).keptTokens()).isEqualTo(30);
        assertThat(result.kept().get(1).type()).isEqualTo(SegmentType.SYSTEM);
        assertThat(result.kept().get(1).trimmed()).isFalse();
        assertThat(result.keptTokens()).isEqualTo(50);
    }

    @Test
    @DisplayName("不可裁剪片段放不下 - 整段丢弃")
    void dropsNonTrimmable() {
        List<ContextSegment> segments = List.of(
                new ContextSegment(0, SegmentType.SYSTEM, text(60), false));

        ContextAssembler.Result result = ContextAssembler.assemble(
                100, ContextAssembler.Options.of(0.5, 0, 0), segments, COUNTER, TRIMMER);

        assertThat(result.kept()).isEmpty();
        assertThat(result.dropped()).hasSize(1);
        assertThat(result.dropped().get(0).type()).isEqualTo(SegmentType.SYSTEM);
        assertThat(result.hasDropped()).isTrue();
    }

    @Test
    @DisplayName("预算公式 - 窗口比例、硬上限、输出预留")
    void budgetFormula() {
        assertThat(ContextAssembler.computeBudget(10_000,
                ContextAssembler.Options.of(0.6, 0, 0))).isEqualTo(6_000);
        assertThat(ContextAssembler.computeBudget(10_000,
                ContextAssembler.Options.of(0.6, 1_000, 0))).isEqualTo(1_000);
        assertThat(ContextAssembler.computeBudget(10_000,
                ContextAssembler.Options.of(0.6, 1_000, 200))).isEqualTo(800);
        // 非法窗口走兜底，非法比例回落 0.6
        assertThat(ContextAssembler.computeBudget(0,
                ContextAssembler.Options.of(-1, 0, 0)))
                .isEqualTo((int) Math.floor(ContextAssembler.DEFAULT_FALLBACK_WINDOW_TOKENS * 0.6));
        assertThat(ContextAssembler.computeBudget(100,
                ContextAssembler.Options.of(0.5, 0, 500))).isZero();
    }

    @Test
    @DisplayName("预算充足 - 原样保留且按 index 排序")
    void underBudgetKeepsOriginal() {
        List<ContextSegment> segments = List.of(
                new ContextSegment(2, SegmentType.RETRIEVAL, "retrieval"),
                new ContextSegment(0, SegmentType.SYSTEM, "system", false),
                new ContextSegment(1, SegmentType.CURRENT_QUESTION, "question", false));

        ContextAssembler.Result result = ContextAssembler.assemble(
                1_000, ContextAssembler.Options.defaults(), segments, COUNTER, TRIMMER);

        assertThat(result.kept()).extracting(ContextAssembler.KeptSegment::index)
                .containsExactly(0, 1, 2);
        assertThat(result.kept()).allMatch(k -> !k.trimmed());
        assertThat(result.keptTokens()).isEqualTo(result.originalTokens());
        assertThat(result.singleSourceSaturated()).isFalse();
        assertThat(result.contentOf(1)).isEqualTo("question");
    }

    @Test
    @DisplayName("单条来源饱和 - 仅保留 1 段且占满预算")
    void singleSourceSaturated() {
        List<ContextSegment> segments = List.of(
                new ContextSegment(0, SegmentType.RETRIEVAL, text(95)),
                new ContextSegment(1, SegmentType.EARLY_TOOL_RESULT, text(20), false));

        ContextAssembler.Result result = ContextAssembler.assemble(
                100, ContextAssembler.Options.of(1.0, 0, 0), segments, COUNTER, TRIMMER);

        assertThat(result.kept()).hasSize(1);
        assertThat(result.singleSourceSaturated()).isTrue();
        assertThat(result.droppedTokensByType()).containsEntry(SegmentType.EARLY_TOOL_RESULT, 20);
    }

    @Test
    @DisplayName("空输入 - 返回空结果")
    void emptyInput() {
        ContextAssembler.Result result = ContextAssembler.assemble(
                100, null, null, COUNTER, TRIMMER);
        assertThat(result.kept()).isEmpty();
        assertThat(result.dropped()).isEmpty();
        assertThat(result.contentOf(0)).isNull();
        assertThat(result.droppedTokensByType()).isEqualTo(Map.of());
    }
}
