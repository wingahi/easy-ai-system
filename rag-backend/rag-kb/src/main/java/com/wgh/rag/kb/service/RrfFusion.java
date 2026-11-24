package com.wgh.rag.kb.service;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RRF（Reciprocal Rank Fusion）多路召回融合。
 * rrfScore(d) = Σ_路 1 / (k + rank_路(d))，k 默认 60。
 * 融合仅决定排序：输出的 match 保留其原始分数（向量余弦 / 关键词 similarity），
 * 保证下游 minScore 阈值与命中率指标语义不变。
 */
public final class RrfFusion {

    public static final int DEFAULT_K = 60;

    private RrfFusion() {
    }

    /**
     * 融合多路已排序结果，按 RRF 分数降序取 limit 条。
     * 同一路内排名即列表顺序；跨路同一文档以 embeddingId（缺省回退文本内容）判重。
     */
    public static List<EmbeddingMatch<TextSegment>> fuse(List<List<EmbeddingMatch<TextSegment>>> rankedLists,
                                                         int limit) {
        return fuse(rankedLists, DEFAULT_K, limit);
    }

    public static List<EmbeddingMatch<TextSegment>> fuse(List<List<EmbeddingMatch<TextSegment>>> rankedLists,
                                                         int k, int limit) {
        return fuse(rankedLists, null, k, limit);
    }

    /**
     * 带权融合：weights 与 rankedLists 按下标对齐，缺省或非正值按 1.0 处理。
     * <p>rrfScore(d) = Σ_路 weight_路 / (k + rank_路(d))；权重只影响排序，不改变原始分数语义。</p>
     */
    public static List<EmbeddingMatch<TextSegment>> fuse(List<List<EmbeddingMatch<TextSegment>>> rankedLists,
                                                         List<Double> weights, int k, int limit) {
        Map<String, EmbeddingMatch<TextSegment>> best = new LinkedHashMap<>();
        Map<String, Double> rrfScores = new LinkedHashMap<>();
        for (int listIndex = 0; listIndex < rankedLists.size(); listIndex++) {
            List<EmbeddingMatch<TextSegment>> ranked = rankedLists.get(listIndex);
            if (ranked == null) continue;
            double weight = weightAt(weights, listIndex);
            for (int rank = 0; rank < ranked.size(); rank++) {
                EmbeddingMatch<TextSegment> m = ranked.get(rank);
                String key = keyOf(m);
                rrfScores.merge(key, weight / (k + rank + 1), Double::sum);
                // 保留原始分数更高的一方作为代表（向量分数通常高于 similarity）
                EmbeddingMatch<TextSegment> prev = best.get(key);
                if (prev == null || score(prev) < score(m)) {
                    best.put(key, m);
                }
            }
        }
        List<Map.Entry<String, Double>> sorted = new ArrayList<>(rrfScores.entrySet());
        sorted.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        List<EmbeddingMatch<TextSegment>> out = new ArrayList<>(Math.min(limit, sorted.size()));
        for (Map.Entry<String, Double> e : sorted) {
            if (out.size() >= limit) break;
            out.add(best.get(e.getKey()));
        }
        return out;
    }

    private static double weightAt(List<Double> weights, int index) {
        if (weights == null || index < 0 || index >= weights.size()) return 1.0;
        Double weight = weights.get(index);
        return weight == null || weight <= 0 ? 1.0 : weight;
    }

    private static String keyOf(EmbeddingMatch<TextSegment> m) {
        if (m.embeddingId() != null) return m.embeddingId();
        return m.embedded() != null && m.embedded().text() != null ? "text:" + m.embedded().text() : "null";
    }

    private static double score(EmbeddingMatch<TextSegment> m) {
        return m.score() != null ? m.score() : 0.0;
    }
}
