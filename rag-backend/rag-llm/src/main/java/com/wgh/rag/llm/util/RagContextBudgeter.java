package com.wgh.rag.llm.util;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 检索上下文 Token 预算器（纯函数，无状态）。
 *
 * <p>用途：把检索结果按 Token 预算做确定性裁剪，避免 topK 调大 / 长文档 / 多段引用时
 * 由模型端静默截断，或直接报超窗。规则：
 * <ol>
 *   <li>按分数（rerank / RRF / 余弦，均由 {@link EmbeddingMatch#score()} 承载）降序优先保留；</li>
 *   <li>被丢弃的 chunk 不会出现在拼接文本中，调用方必须用返回的 kept 列表生成引用来源；</li>
 *   <li>单条 chunk 就撑满预算时按句界裁剪并追加截断标记，而不是整条丢弃（保证至少有一条依据）。</li>
 * </ol>
 *
 * <p>预算公式：{@code min(硬上限, floor(模型窗口 × 输入占比) - 已有 prompt Tokens)}，
 * 结果不小于单条最少保留值。剩余窗口留给模型输出与 tokenizer 估算误差。
 */
public final class RagContextBudgeter {

    /** 检索上下文 Token 硬上限（默认 6000：5×512 字 ≈ 2500 Tokens，留约 2.4 倍余量） */
    public static final int DEFAULT_MAX_CONTEXT_TOKENS = 6000;
    /** 模型未配置 maxContextTokens 时的兜底窗口（8192，兼容最常见的 8k 上下文模型） */
    public static final int DEFAULT_FALLBACK_WINDOW_TOKENS = 8192;
    /** 输入侧最多占模型窗口比例（其余 40% 留给输出与估算误差） */
    public static final double DEFAULT_INPUT_WINDOW_RATIO = 0.6;
    /** 预算耗尽时单条 chunk 的最小保留 Token，避免整条上下文丢失 */
    public static final int DEFAULT_MIN_CHUNK_TOKENS = 120;

    /** 单条 chunk 撑满预算的告警阈值：保留 1 条 且（有丢弃 或 发生截断） */
    private static final String TRUNCATE_MARK = "\n…（该片段超出上下文预算，已截断）";
    private static final int TRUNCATE_MARK_TOKENS = TokenEstimator.estimate(TRUNCATE_MARK);
    private static final String SEGMENT_TAIL = "\n\n";

    private RagContextBudgeter() {
    }

    /** 被丢弃的检索片段，用于日志/指标可观测。 */
    public record DroppedChunk(int rank, String fileName, Integer chunkIndex, Double score, int tokens) {
        @Override
        public String toString() {
            return "rank=" + rank + ",file=" + fileName + ",chunk=" + chunkIndex
                    + ",score=" + score + ",tokens=" + tokens;
        }
    }

    /** 裁剪结果。 */
    public record Result(
            List<EmbeddingMatch<TextSegment>> kept,
            int budgetTokens,
            int originalCount,
            int originalTokens,
            int keptCount,
            int keptTokens,
            List<DroppedChunk> dropped,
            boolean truncated) {

        /** 单条就撑满预算：已达 map-reduce 的触发阈值（当前版本仅告警，不做分段摘要） */
        public boolean singleChunkSaturated() {
            return keptCount == 1 && (!dropped.isEmpty() || truncated);
        }
    }

    /**
     * 计算本次可用的检索上下文 Token 预算。
     *
     * @param modelMaxContextTokens 模型上下文窗口（为空/非正数时使用兜底窗口）
     * @param promptTokens          已占用的输入 Tokens（system prompt + 历史 + 当前问题）
     * @param maxContextTokens      检索上下文硬上限（<=0 使用默认值）
     * @param inputWindowRatio      输入侧占模型窗口比例（非法值使用默认 0.6）
     * @param minChunkTokens        单条最小保留 Token（<=0 使用默认值）
     */
    public static int computeBudget(Integer modelMaxContextTokens, int promptTokens,
                                    int maxContextTokens, double inputWindowRatio, int minChunkTokens) {
        int window = modelMaxContextTokens != null && modelMaxContextTokens > 0
                ? modelMaxContextTokens : DEFAULT_FALLBACK_WINDOW_TOKENS;
        int cap = maxContextTokens > 0 ? maxContextTokens : DEFAULT_MAX_CONTEXT_TOKENS;
        double ratio = inputWindowRatio > 0 && inputWindowRatio <= 1
                ? inputWindowRatio : DEFAULT_INPUT_WINDOW_RATIO;
        int inputBudget = (int) Math.floor(window * ratio);
        int available = Math.min(cap, inputBudget - Math.max(promptTokens, 0));
        int floor = minChunkTokens > 0 ? minChunkTokens : DEFAULT_MIN_CHUNK_TOKENS;
        return Math.max(available, floor);
    }

    /** 使用默认参数计算预算（供拿不到模型窗口的 Agent / 工作流链路使用）。 */
    public static int defaultBudget(int promptTokens) {
        return computeBudget(null, promptTokens, DEFAULT_MAX_CONTEXT_TOKENS,
                DEFAULT_INPUT_WINDOW_RATIO, DEFAULT_MIN_CHUNK_TOKENS);
    }

    /** 使用默认参数裁剪（供拿不到模型窗口的 Agent / 工作流链路使用）。 */
    public static Result trimWithDefaults(List<EmbeddingMatch<TextSegment>> matches, int promptTokens) {
        return trim(matches, defaultBudget(promptTokens), DEFAULT_MIN_CHUNK_TOKENS);
    }

    /**
     * 按预算裁剪检索结果。返回的 kept 列表按分数降序，调用方必须以其生成 prompt 与引用来源。
     */
    public static Result trim(List<EmbeddingMatch<TextSegment>> matches, int budgetTokens, int minChunkTokens) {
        List<EmbeddingMatch<TextSegment>> valid = new ArrayList<>();
        if (matches != null) {
            for (EmbeddingMatch<TextSegment> m : matches) {
                if (m != null && m.embedded() != null) valid.add(m);
            }
        }

        int originalTokens = 0;
        for (EmbeddingMatch<TextSegment> m : valid) originalTokens += chunkTokens(m);

        List<EmbeddingMatch<TextSegment>> sorted = new ArrayList<>(valid);
        sorted.sort(Comparator.comparingDouble(
                (EmbeddingMatch<TextSegment> m) -> m.score() == null ? Double.NEGATIVE_INFINITY : m.score())
                .reversed());

        int floor = minChunkTokens > 0 ? minChunkTokens : DEFAULT_MIN_CHUNK_TOKENS;
        int remaining = Math.max(budgetTokens, floor);
        List<EmbeddingMatch<TextSegment>> kept = new ArrayList<>();
        List<DroppedChunk> dropped = new ArrayList<>();
        boolean truncated = false;

        for (int i = 0; i < sorted.size(); i++) {
            EmbeddingMatch<TextSegment> match = sorted.get(i);
            int cost = chunkTokens(match);
            if (cost <= remaining) {
                kept.add(match);
                remaining -= cost;
                continue;
            }
            if (kept.isEmpty()) {
                // 第一条就超预算：按句界裁剪后保留，避免"整个上下文为空"
                int textBudget = remaining - bannerTokens(match) - TRUNCATE_MARK_TOKENS;
                String text = match.embedded().text();
                String clipped = clipToTokens(text, textBudget);
                if (!clipped.isEmpty() && clipped.length() < text.length()) {
                    TextSegment segment = TextSegment.from(clipped + TRUNCATE_MARK, match.embedded().metadata());
                    kept.add(new EmbeddingMatch<>(match.score(), match.embeddingId(), match.embedding(), segment));
                    truncated = true;
                    remaining = 0;
                    continue;
                }
            }
            dropped.add(toDropped(i + 1, match, cost));
        }

        int keptTokens = 0;
        for (EmbeddingMatch<TextSegment> m : kept) keptTokens += chunkTokens(m);
        return new Result(List.copyOf(kept), Math.max(budgetTokens, floor), valid.size(), originalTokens,
                kept.size(), keptTokens, List.copyOf(dropped), truncated);
    }

    /** 单条 chunk 拼进上下文后的 Token 成本（含来源标注与段落分隔）。 */
    public static int chunkTokens(EmbeddingMatch<TextSegment> match) {
        if (match == null || match.embedded() == null) return 0;
        return bannerTokens(match) + TokenEstimator.estimate(match.embedded().text())
                + TokenEstimator.estimate(SEGMENT_TAIL);
    }

    private static int bannerTokens(EmbeddingMatch<TextSegment> match) {
        String kbName = metadataString(match, "kbName");
        String fileName = metadataString(match, "fileName");
        if (kbName == null && fileName == null) return 0;
        return TokenEstimator.estimate("【来源: " + (kbName == null ? "" : kbName)
                + " - " + (fileName == null ? "" : fileName) + "】\n");
    }

    private static String metadataString(EmbeddingMatch<TextSegment> match, String key) {
        return match.embedded() != null && match.embedded().metadata() != null
                ? match.embedded().metadata().getString(key) : null;
    }

    private static DroppedChunk toDropped(int rank, EmbeddingMatch<TextSegment> match, int tokens) {
        return new DroppedChunk(rank, metadataString(match, "fileName"),
                match.embedded().metadata() != null ? match.embedded().metadata().getInteger("chunkIndex") : null,
                match.score(), tokens);
    }

    /**
     * 按 Token 估算裁剪文本，优先在句末/换行处截断；放得下时原样返回。
     */
    private static String clipToTokens(String text, int maxTokens) {
        if (text == null || text.isEmpty() || maxTokens <= 0) return "";
        int cjk = 0;
        int other = 0;
        int cut = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                cjk++;
            } else {
                other++;
            }
            if (cjk + (other + 3) / 4 > maxTokens) break;
            cut = i + 1;
        }
        if (cut >= text.length()) return text;
        int boundary = lastSentenceBoundary(text, cut);
        return text.substring(0, boundary > 0 ? boundary : cut);
    }

    private static int lastSentenceBoundary(String text, int cut) {
        int floor = (int) (cut * 0.6);
        for (int i = cut - 1; i >= floor; i--) {
            char c = text.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '；' || c == '\n'
                    || c == '.' || c == '!' || c == '?') {
                return i + 1;
            }
        }
        return cut;
    }

    private static boolean isCjk(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.HIRAGANA
                || block == Character.UnicodeBlock.KATAKANA
                || block == Character.UnicodeBlock.HANGUL_SYLLABLES;
    }
}
