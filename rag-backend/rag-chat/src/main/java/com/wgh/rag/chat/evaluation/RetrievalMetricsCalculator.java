package com.wgh.rag.chat.evaluation;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 检索指标计算器
 * 在线场景下没有人工标注的 ground truth，采用「响应反推引用」策略：
 * 用 LLM 响应反向判定哪些检索 chunks 被实际引用，把这些视为相关项，
 * 回看其检索排名计算 MRR / NDCG。
 */
public class RetrievalMetricsCalculator {

    /**
     * 检索指标结果
     */
    public record RetrievalMetrics(double mrr, double ndcg, int citedCount, int totalRetrieved) {}

    /**
     * 判定每个检索 chunk 是否被响应引用
     * 复用与 RagChatService.calculateAccuracyRate 相同的字符匹配启发式：
     * 中文 3 字滑窗 + 英文/数字长度 >= 4 的词，命中比例 >= 8% 视为被引用
     */
    public static boolean[] computeCitedFlags(String response, List<EmbeddingMatch<TextSegment>> matches) {
        boolean[] flags = new boolean[matches.size()];
        if (matches.isEmpty() || response == null || response.isBlank()) return flags;

        // 剥离 Markdown 符号（LLM 常输出 **粗体**、`代码` 等）
        String respNorm = response.replaceAll("[\\*\\`#_~\\[\\](){}<>|\\\\]", "").toLowerCase();

        for (int idx = 0; idx < matches.size(); idx++) {
            EmbeddingMatch<TextSegment> match = matches.get(idx);
            String content = match.embedded() != null ? match.embedded().text() : null;
            if (content == null || content.isBlank()) continue;

            Set<String> phrases = new HashSet<>();
            // 中文 3 字滑窗（前 200 字内抽样，步长 3，最多 40 条）
            String cleaned = content.replaceAll("[\\s\\p{P}\\p{S}]+", "");
            for (int i = 0; i + 3 <= Math.min(cleaned.length(), 200); i += 3) {
                String gram = cleaned.substring(i, i + 3);
                if (gram.matches("[\\u4e00-\\u9fa5]{3}")) phrases.add(gram);
                if (phrases.size() >= 40) break;
            }
            // 英文/数字长度 >= 4 的词
            for (String w : content.split("[^A-Za-z0-9]+")) {
                if (w.length() >= 4) phrases.add(w.toLowerCase());
                if (phrases.size() >= 80) break;
            }
            if (phrases.isEmpty()) continue;

            int hits = 0;
            for (String p : phrases) {
                if (respNorm.contains(p.toLowerCase())) hits++;
            }
            if (hits >= Math.max(1, (int) (phrases.size() * 0.08))) {
                flags[idx] = true;
            }
        }
        return flags;
    }

    /**
     * 基于 citedFlags 计算 MRR 与 NDCG
     *
     * MRR  = 第一个被引用 chunk 在检索结果中的 1/rank，无被引用则为 0
     * NDCG = DCG / IDCG，其中 DCG = Σ rel_i / log2(i+2)，IDCG 假设所有被引用 chunks 都排在最前
     */
    public static RetrievalMetrics compute(String response, List<EmbeddingMatch<TextSegment>> matches) {
        boolean[] flags = computeCitedFlags(response, matches);
        int citedCount = 0;
        double mrr = 0.0;
        double dcg = 0.0;

        for (int i = 0; i < flags.length; i++) {
            if (flags[i]) {
                citedCount++;
                if (mrr == 0.0) {
                    mrr = 1.0 / (i + 1);
                }
                dcg += 1.0 / log2(i + 2);
            }
        }

        double ndcg = 0.0;
        if (citedCount > 0) {
            double idcg = 0.0;
            for (int i = 0; i < citedCount; i++) {
                idcg += 1.0 / log2(i + 2);
            }
            ndcg = idcg > 0 ? dcg / idcg : 0.0;
        }

        return new RetrievalMetrics(mrr, ndcg, citedCount, matches.size());
    }

    private static double log2(double x) {
        return Math.log(x) / Math.log(2);
    }
}
