package com.wgh.rag.llm.util;

import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * map-reduce 分段摘要（纯逻辑 + 单次模型调用封装）。
 *
 * <p>触发条件：单条检索 chunk 的 Token 成本已超过本次检索上下文预算
 * （{@link #shouldApply(int, int)}）。此时把该 chunk 按句界切成若干段，
 * 逐段压缩为要点，再合并成一段摘要，避免「只保留开头 + 截断标记」丢失后半部分信息。
 *
 * <p>失败降级：任一段调用失败只跳过该段；全部失败时返回 {@code reduced=false}，
 * 调用方继续走原有的截断逻辑。
 */
@Slf4j
public final class MapReduceChunkSummarizer {

    /** 单段摘要提示词模板 */
    private static final String SEGMENT_PROMPT = """
            请压缩下面的文档片段，供后续问答使用：
            - 保留数字、日期、专有名词、结论、约束条件与例外；
            - 删除寒暄、重复表述与无关铺垫；
            - 不要编造原文没有的信息；
            - 不超过 %d 字。
            
            文档片段：
            %s
            """;

    /** 合并后摘要的固定前缀 */
    private static final String MERGED_HEADER = "【分段摘要（原文过长，以下为要点合并）】\n";

    private MapReduceChunkSummarizer() {
    }

    /** 摘要结果 */
    public record Result(String text, int segments, boolean reduced) {
    }

    /** 单条 chunk 是否已超过预算，需要触发分段摘要 */
    public static boolean shouldApply(int chunkTokens, int budgetTokens) {
        return chunkTokens > 0 && budgetTokens > 0 && chunkTokens > budgetTokens;
    }

    /**
     * 分段摘要。
     *
     * @param text               原始 chunk 正文
     * @param chatModel          用于摘要的模型
     * @param maxSegments        最多切分几段（>=1）
     * @param segmentMaxTokens   单段摘要的输出 Token 上限
     * @param budgetTokens       合并后允许占用的 Token 上限
     */
    public static Result summarize(String text, ChatModel chatModel, int maxSegments,
                                   int segmentMaxTokens, int budgetTokens) {
        if (text == null || text.isBlank() || chatModel == null || maxSegments <= 0) {
            return new Result(text, 0, false);
        }
        List<String> parts = split(text, maxSegments);
        if (parts.size() <= 1) {
            return new Result(text, parts.size(), false);
        }
        List<String> summaries = new ArrayList<>();
        for (String part : parts) {
            try {
                String prompt = SEGMENT_PROMPT.formatted(Math.max(segmentMaxTokens * 2, 120), part);
                String summary = chatModel.chat(prompt);
                if (summary != null && !summary.isBlank()) {
                    summaries.add(summary.trim());
                }
            } catch (Exception e) {
                log.warn("分段摘要第 {} 段失败（跳过）: reason={}", summaries.size() + 1, e.getMessage());
            }
        }
        if (summaries.isEmpty()) {
            return new Result(text, parts.size(), false);
        }
        StringBuilder merged = new StringBuilder(MERGED_HEADER);
        for (int i = 0; i < summaries.size(); i++) {
            merged.append(i + 1).append(". ").append(summaries.get(i)).append('\n');
        }
        String clipped = RagContextBudgeter.clip(merged.toString().trim(), budgetTokens);
        return new Result(clipped, parts.size(), true);
    }

    /**
     * 按句界把文本切成最多 {@code maxSegments} 段，保持原顺序。
     * 段数不超过 1 时原样返回单元素列表（调用方据此跳过 map-reduce）。
     */
    public static List<String> split(String text, int maxSegments) {
        List<String> result = new ArrayList<>();
        if (text == null || text.isBlank() || maxSegments <= 0) {
            return result;
        }
        List<String> sentences = splitSentences(text);
        if (sentences.size() <= 1 || maxSegments == 1) {
            result.add(text);
            return result;
        }
        int segments = Math.min(maxSegments, sentences.size());
        int per = (int) Math.ceil((double) sentences.size() / segments);
        for (int i = 0; i < sentences.size(); i += per) {
            result.add(String.join("", sentences.subList(i, Math.min(i + per, sentences.size()))));
        }
        return result;
    }

    /** 按中文/英文句末标点与换行切句，标点保留在句尾。 */
    static List<String> splitSentences(String text) {
        List<String> sentences = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            current.append(c);
            boolean end = c == '。' || c == '！' || c == '？' || c == '；' || c == '\n';
            if (!end && (c == '.' || c == '!' || c == '?')) {
                // 英文句末：后一位是空白或结尾才算断句，避免拆开小数与缩写
                boolean nextBlank = i + 1 >= text.length() || Character.isWhitespace(text.charAt(i + 1));
                end = nextBlank;
            }
            if (end) {
                sentences.add(current.toString());
                current.setLength(0);
            }
        }
        if (!current.isEmpty()) {
            sentences.add(current.toString());
        }
        return sentences;
    }
}
