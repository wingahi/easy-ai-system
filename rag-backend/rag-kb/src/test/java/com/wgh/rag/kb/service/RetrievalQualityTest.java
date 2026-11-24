package com.wgh.rag.kb.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RetrievalQuality 单测：召回覆盖率、命中率、Recall@K（含无标注不可计算）
 */
@DisplayName("检索质量指标计算测试")
class RetrievalQualityTest {

    /** 召回覆盖率 = 返回条数 / topK，用于衡量 topK 填充程度，与相关性无关。 */
    @Test
    @DisplayName("召回覆盖率 - 返回条数 / topK，超 topK 封顶 1.0")
    void coverage() {
        assertThat(RetrievalQuality.coverage(5, 5)).isEqualTo(1.0);
        assertThat(RetrievalQuality.coverage(3, 5)).isEqualTo(0.6);
        assertThat(RetrievalQuality.coverage(0, 5)).isZero();
        assertThat(RetrievalQuality.coverage(8, 5)).isEqualTo(1.0);
        assertThat(RetrievalQuality.coverage(3, 0)).isZero();
    }

    /** 命中率 = 相关条数 / 实际返回条数；无返回时为 0。 */
    @Test
    @DisplayName("命中率 - 相关条数 / 返回条数")
    void hitRate() {
        assertThat(RetrievalQuality.hitRate(4, 5)).isEqualTo(0.8);
        assertThat(RetrievalQuality.hitRate(5, 5)).isEqualTo(1.0);
        assertThat(RetrievalQuality.hitRate(0, 5)).isZero();
        assertThat(RetrievalQuality.hitRate(0, 0)).isZero();
    }

    /** Recall@K 的分母是标注的相关集合，不是 topK：相关 4 条全部召回 → 1.0。 */
    @Test
    @DisplayName("Recall@K - 分母为标注相关集合，与 topK 无关")
    void recallDenominatorIsRelevantSet() {
        Set<String> relevant = Set.of("a", "b", "c", "d");
        // 分母 4（相关集合），命中 2 → 0.5（不是 2/5 = 0.4）
        assertThat(RetrievalQuality.recallAtK(relevant, List.of("a", "b", "x", "y", "z"))).isEqualTo(0.5);
        assertThat(RetrievalQuality.recallAtK(relevant, List.of("a", "b", "c", "d", "x"))).isEqualTo(1.0);
        assertThat(RetrievalQuality.recallAtK(relevant, List.of("x", "y"))).isZero();
    }

    /** 无标注（空集合）时 Recall@K 不可计算 → null，不做虚假回填。 */
    @Test
    @DisplayName("Recall@K - 无标注相关集合时返回 null（不可计算）")
    void recallUndefinedWithoutGroundTruth() {
        assertThat(RetrievalQuality.recallAtK(Set.of(), List.of("a", "b"))).isNull();
        assertThat(RetrievalQuality.recallAtK(null, List.of("a", "b"))).isNull();
    }

    /** MRR 取第一个相关结果的排名倒数；有标注但未命中记 0，无标注不可计算。 */
    @Test
    @DisplayName("MRR@K - 首个相关结果排名倒数，未命中记 0，无标注返回 null")
    void mrrAtK() {
        Set<String> relevant = Set.of("a", "c");
        assertThat(RetrievalQuality.mrrAtK(relevant, List.of("b", "a", "c"))).isEqualTo(0.5);
        assertThat(RetrievalQuality.mrrAtK(relevant, List.of("a", "b"))).isEqualTo(1.0);
        assertThat(RetrievalQuality.mrrAtK(relevant, List.of("x", "y"))).isZero();
        assertThat(RetrievalQuality.mrrAtK(relevant, List.of())).isZero();
        assertThat(RetrievalQuality.mrrAtK(Set.of(), List.of("a"))).isNull();
    }

    /** 二值 NDCG@K：DCG 折损 log2(rank+1)，IDCG 取理想排序前 K 项。 */
    @Test
    @DisplayName("NDCG@K - 二值相关手算校验，无标注返回 null")
    void ndcgAtK() {
        Set<String> relevant = Set.of("a", "b");
        // 排名 [b(相关), x(不相关), a(相关)]：DCG = 1/log2(2) + 1/log2(4) = 1.5
        // IDCG = 1 + 1/log2(3) ≈ 1.6309 → NDCG ≈ 0.9197
        assertThat(RetrievalQuality.ndcgAtK(relevant, List.of("b", "x", "a"), 3))
                .isCloseTo(0.9197, org.assertj.core.data.Offset.offset(0.0001));
        // 前 K 条均不相关 → 0
        assertThat(RetrievalQuality.ndcgAtK(relevant, List.of("x"), 1)).isZero();
        // 无标注 → null
        assertThat(RetrievalQuality.ndcgAtK(Set.of(), List.of("a"), 5)).isNull();
        assertThat(RetrievalQuality.ndcgAtK(relevant, List.of("a"), 0)).isNull();
    }

    /** 带等级 NDCG：gain 取等级，理想排序按等级降序。 */
    @Test
    @DisplayName("NDCG@K - 相关性等级手算校验")
    void ndcgAtKWithGrades() {
        java.util.Map<String, Double> grades = java.util.Map.of("a", 2.0, "b", 1.0);
        // 排名 [b, a]：DCG = 1/1 + 2/log2(3) ≈ 2.2619；IDCG = 2 + 1/log2(3) ≈ 2.6309
        assertThat(RetrievalQuality.ndcgAtK(grades, List.of("b", "a"), 2))
                .isCloseTo(0.8597, org.assertj.core.data.Offset.offset(0.0001));
        // 负等级按 0 处理
        assertThat(RetrievalQuality.ndcgAtK(java.util.Map.of("a", -1.0), List.of("a"), 1)).isNull();
    }
}
