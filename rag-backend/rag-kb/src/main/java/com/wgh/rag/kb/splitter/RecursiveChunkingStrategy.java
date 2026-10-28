package com.wgh.rag.kb.splitter;

import com.wgh.rag.config.property.RagProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * RECURSIVE：递归字符分块（默认基线）：\n\n → \n → 句 → 子句 → 字
 */
@Component
@RequiredArgsConstructor
public class RecursiveChunkingStrategy implements ChunkingStrategy {

    private final RagProperties ragProperties;

    /** 递归分块的分隔符层级：段落 → 行 → 句 → 子句 → 词 → 字 */
    private static final String[] RECURSIVE_SEPARATORS = {"\n\n", "\n", "。", "！", "？", "；", ".", "!", "?", ";", "，", ",", " ", ""};

    @Override
    public TextSplitter.Strategy type() {
        return TextSplitter.Strategy.RECURSIVE;
    }

    @Override
    public int priority() {
        return 5;
    }

    /**
     * 递归字符分块：按分隔符层级逐级降级切割，
     * 尽量在高层级语义边界（段落/行/句）切分；相邻 chunk 保留 overlap 重叠。
     */
    @Override
    public List<String> split(List<String> paragraphs, String customConfig) {
        return splitRecursive(String.join("\n", paragraphs));
    }

    /**
     * 对整段文本执行递归分块（含相邻 chunk 重叠）
     */
    public List<String> splitRecursive(String text) {
        int maxLength = ragProperties.getSplit().getMaxLength();
        int overlap = ragProperties.getSplit().getOverlapLength();
        List<String> pieces = cut(text);
        return mergeWithOverlap(pieces, maxLength, overlap);
    }

    /**
     * 仅递归切割（不加重叠），供 FAQ 等策略对超长单元再切分
     */
    public List<String> cut(String text) {
        return recursiveCut(text, ragProperties.getSplit().getMaxLength(), 0);
    }

    /**
     * 递归切割：文本超长时，用当前层级分隔符切开，对仍超长的片段用下一层分隔符递归。
     */
    private List<String> recursiveCut(String text, int maxLength, int level) {
        List<String> result = new ArrayList<>();
        if (text == null || text.isBlank()) return result;
        if (text.length() <= maxLength) {
            result.add(text.trim());
            return result;
        }

        String sep = level < RECURSIVE_SEPARATORS.length ? RECURSIVE_SEPARATORS[level] : "";
        List<String> parts;
        if (sep.isEmpty()) {
            // 最底层：按 maxLength 硬切
            parts = new ArrayList<>();
            for (int i = 0; i < text.length(); i += maxLength) {
                parts.add(text.substring(i, Math.min(i + maxLength, text.length())));
            }
        } else {
            // 按分隔符切开，保留分隔符在片段末尾（句子的句号属于上一句）
            parts = splitKeepSeparator(text, sep);
        }

        StringBuilder buf = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (part.length() > maxLength) {
                // 单片超长 → flush + 下一层递归
                if (buf.length() > 0) {
                    result.add(buf.toString().trim());
                    buf.setLength(0);
                }
                result.addAll(recursiveCut(part, maxLength, level + 1));
            } else if (buf.length() + part.length() > maxLength) {
                result.add(buf.toString().trim());
                buf = new StringBuilder(part);
            } else {
                buf.append(part);
            }
        }
        if (buf.length() > 0 && !buf.toString().isBlank()) {
            result.add(buf.toString().trim());
        }
        return result;
    }

    /** 按分隔符切分，分隔符保留在片段末尾 */
    private List<String> splitKeepSeparator(String text, String sep) {
        List<String> parts = new ArrayList<>();
        int idx = 0;
        while (idx < text.length()) {
            int next = text.indexOf(sep, idx);
            if (next < 0) {
                parts.add(text.substring(idx));
                break;
            }
            parts.add(text.substring(idx, next + sep.length()));
            idx = next + sep.length();
        }
        return parts;
    }

    /** 相邻 chunk 间加 overlap 重叠（从上一 chunk 末尾截取） */
    private List<String> mergeWithOverlap(List<String> pieces, int maxLength, int overlap) {
        if (pieces.size() <= 1 || overlap <= 0) return pieces;
        List<String> result = new ArrayList<>();
        String prevTail = "";
        for (String piece : pieces) {
            String merged = prevTail.isEmpty() ? piece : prevTail + piece;
            if (merged.length() > maxLength) {
                // overlap 导致超长则放弃重叠
                merged = piece;
            }
            if (!merged.isBlank()) result.add(merged);
            prevTail = piece.length() > overlap
                    ? piece.substring(piece.length() - overlap) : piece;
        }
        return result;
    }
}
