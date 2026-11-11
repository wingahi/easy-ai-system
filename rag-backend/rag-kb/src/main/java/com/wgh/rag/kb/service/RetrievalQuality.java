package com.wgh.rag.kb.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 检索质量指标计算。
 *
 * <p>Recall@K 的标准定义是「检索到的相关 chunk 数 / 语料库中全部相关 chunk 数」，
 * 分母是人工标注的相关集合，而不是 topK。在线检索链路没有相关性标注，因此：
 * <ul>
 *   <li>{@link #coverage}：召回覆盖率（retrievedCount / topK），衡量 topK 是否被填满，不是召回率</li>
 *   <li>{@link #hitRate}：命中率（相关条数 / 实际返回条数），即 Precision@K</li>
 *   <li>{@link #recallAtK}：仅在提供标注相关集合时可用，无标注返回 null（不可计算）</li>
 *   <li>{@link #mrrAtK}：第一个相关结果的排名倒数；无标注返回 null（不可计算），有标注但未命中返回 0</li>
 *   <li>{@link #ndcgAtK(Set, List, int)}：二值相关 NDCG@K；无标注返回 null（不可计算）</li>
 *   <li>{@link #ndcgAtK(Map, List, int)}：带相关性等级的 NDCG@K（gain 直接取等级，负值按 0 处理）</li>
 * </ul>
 *
 * <p>口径约定：排名从 1 开始；DCG 折损因子为 log2(rank+1)；IDCG 取理想排序（相关度降序）的前 K 项。
 * 当前标注表 {@code wgh_kb_ground_truth} 只有相关 chunk 键集合、没有相关性等级，
 * 因此离线评测默认使用二值相关（gain=1）；若后续引入等级标注，直接调用带等级重载即可，无需改口径。
 */
public final class RetrievalQuality {

    private RetrievalQuality() {
    }

    /**
     * 召回覆盖率 = 实际返回条数 / topK，取值 [0,1]。
     * 只反映 topK 槽位填充程度，与结果是否相关无关。
     */
    public static double coverage(int retrievedCount, int topK) {
        if (topK <= 0) return 0.0;
        return Math.min(1.0, Math.max(0, retrievedCount) / (double) topK);
    }

    /**
     * 命中率（Precision@K）= 相关条数 / 实际返回条数；无返回时定义为 0。
     */
    public static double hitRate(int relevantCount, int retrievedCount) {
        if (retrievedCount <= 0) return 0.0;
        return Math.min(1.0, Math.max(0, relevantCount) / (double) retrievedCount);
    }

    /**
     * 真实召回率 Recall@K = |相关集合 ∩ 检索结果| / |相关集合|。
     * 相关集合为空（无标注）时不可计算，返回 null。
     */
    public static Double recallAtK(Set<String> relevantIds, Collection<String> retrievedIds) {
        if (relevantIds == null || relevantIds.isEmpty()) return null;
        Set<String> retrieved = new HashSet<>();
        if (retrievedIds != null) {
            retrievedIds.stream().filter(Objects::nonNull).forEach(retrieved::add);
        }
        long hit = relevantIds.stream().filter(retrieved::contains).count();
        return hit / (double) relevantIds.size();
    }

    /**
     * MRR@K（单条查询的 Reciprocal Rank）= 第一个相关结果的排名倒数。
     *
     * <p>排名从 1 开始；排序即检索返回顺序（已按相关性降序）。无标注相关集合时不可计算返回 null；
     * 有标注但前 K 条均不相关时返回 0（这是有效结论，不是不可计算）。
     *
     * @param relevantIds 标注相关 chunk 键集合
     * @param rankedIds   检索结果键，按返回顺序（排名 1..n）
     */
    public static Double mrrAtK(Set<String> relevantIds, List<String> rankedIds) {
        if (relevantIds == null || relevantIds.isEmpty()) return null;
        if (rankedIds == null || rankedIds.isEmpty()) return 0.0;
        for (int i = 0; i < rankedIds.size(); i++) {
            String id = rankedIds.get(i);
            if (id != null && relevantIds.contains(id)) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    /**
     * 二值相关 NDCG@K：相关记 gain=1，不相关记 0。
     *
     * <p>DCG = Σ_{i=1..K} gain_i / log2(i+1)；IDCG = 前 min(|相关集合|, K) 个位置的折损和。
     * 无标注相关集合或 K≤0 时不可计算返回 null；有标注但前 K 条均不相关时 DCG=0 → NDCG=0。
     */
    public static Double ndcgAtK(Set<String> relevantIds, List<String> rankedIds, int k) {
        if (relevantIds == null || relevantIds.isEmpty()) return null;
        Map<String, Double> grades = new LinkedHashMap<>();
        for (String id : relevantIds) {
            grades.put(id, 1.0);
        }
        return ndcgAtK(grades, rankedIds, k);
    }

    /**
     * 带相关性等级的 NDCG@K：gain 直接取等级（负数按 0），未出现在等级表中的结果同样按 0。
     *
     * <p>IDCG 用等级降序的理想排序计算。无等级数据或 K≤0 时不可计算返回 null。
     */
    public static Double ndcgAtK(Map<String, Double> relevanceGrades, List<String> rankedIds, int k) {
        if (relevanceGrades == null || relevanceGrades.isEmpty() || k <= 0) return null;

        int limit = rankedIds == null ? 0 : Math.min(k, rankedIds.size());
        double dcg = 0.0;
        for (int i = 0; i < limit; i++) {
            String id = rankedIds.get(i);
            double gain = id == null ? 0.0 : Math.max(0.0, relevanceGrades.getOrDefault(id, 0.0));
            dcg += gain / log2(i + 2);
        }

        List<Double> idealGains = new ArrayList<>();
        for (Double grade : relevanceGrades.values()) {
            if (grade != null && grade > 0) idealGains.add(grade);
        }
        idealGains.sort(Comparator.reverseOrder());
        int idealCount = Math.min(idealGains.size(), k);
        double idcg = 0.0;
        for (int i = 0; i < idealCount; i++) {
            idcg += idealGains.get(i) / log2(i + 2);
        }
        if (idcg <= 0) return null;
        return Math.min(1.0, dcg / idcg);
    }

    private static double log2(double x) {
        return Math.log(x) / Math.log(2);
    }
}
