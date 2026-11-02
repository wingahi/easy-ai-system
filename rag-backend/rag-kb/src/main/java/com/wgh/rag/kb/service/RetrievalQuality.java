package com.wgh.rag.kb.service;

import java.util.Collection;
import java.util.HashSet;
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
 * </ul>
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
            retrievedIds.stream().filter(java.util.Objects::nonNull).forEach(retrieved::add);
        }
        long hit = relevantIds.stream().filter(retrieved::contains).count();
        return hit / (double) relevantIds.size();
    }
}
