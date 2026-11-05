package com.wgh.rag.kb.splitter;

import com.wgh.rag.config.property.RagProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * PARAGRAPH：按段落分割 - 滑窗合并多个段落，相邻 chunk 保留 overlap 重叠，
 * 避免跨段落语义断裂；超长段落回退按长度切分
 */
@Component
@RequiredArgsConstructor
public class ParagraphChunkingStrategy implements ChunkingStrategy {

    private final RagProperties ragProperties;
    private final LengthChunkingStrategy lengthStrategy;

    @Override
    public TextSplitter.Strategy type() {
        return TextSplitter.Strategy.PARAGRAPH;
    }

    @Override
    public int priority() {
        return 2;
    }

    @Override
    public List<String> split(List<String> paragraphs, String customConfig) {
        int maxLength = ragProperties.getSplit().getMaxLength();
        int overlap = Math.max(0, Math.min(ragProperties.getSplit().getOverlapLength(), Math.max(0, maxLength - 1)));

        List<String> chunks = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        // buf 是否只承载上一块末尾的 overlap（不含任何新段落内容）
        boolean overlapOnly = false;

        for (String para : paragraphs) {
            if (para == null || para.isBlank()) continue;

            // 段落本身就超长 → 落盘缓冲 + 段落内滑窗；以最后一块的末尾 overlap 续接
            if (para.length() > maxLength) {
                if (!overlapOnly && !buf.isEmpty()) {
                    chunks.add(buf.toString());
                }
                buf.setLength(0);
                overlapOnly = false;
                List<String> pieces = lengthStrategy.splitByLength(para);
                chunks.addAll(pieces);
                if (overlap > 0 && !pieces.isEmpty()) {
                    buf.append(last(pieces.get(pieces.size() - 1), overlap));
                    overlapOnly = true;
                }
                continue;
            }

            // 加上这段会超 → 处理缓冲（buf 为空时直接进入追加，避免产出空块）
            if (!buf.isEmpty() && buf.length() + para.length() + 1 > maxLength) {
                if (overlapOnly) {
                    // 缓冲只是重叠尾部，独立成块会产生 overlap+微段落 的碎片，直接丢弃
                    buf.setLength(0);
                    overlapOnly = false;
                } else {
                    String current = buf.toString();
                    // 微小段落优先并入当前块（放得下时），避免碎片块
                    if (overlap > 0 && para.length() < overlap
                            && current.length() + 1 + para.length() <= maxLength) {
                        String merged = current + "\n" + para;
                        chunks.add(merged);
                        buf.setLength(0);
                        buf.append(last(merged, overlap));
                        overlapOnly = true;
                        continue;
                    }
                    chunks.add(current);
                    buf.setLength(0);
                    String tail = last(current, overlap);
                    // tail + 当前段放不下 → 不携带 tail，否则块会超过 maxLength
                    if (tail.length() + para.length() + 1 <= maxLength) {
                        buf.append(tail);
                        overlapOnly = true;
                    }
                }
            }

            if (buf.length() > 0 && buf.charAt(buf.length() - 1) != '\n') buf.append("\n");
            buf.append(para);
            overlapOnly = false;
        }

        if (!overlapOnly && buf.length() > 0) chunks.add(buf.toString());
        return mergeTinyChunks(chunks, maxLength, overlap);
    }

    /**
     * 碎片块规则：长度小于 overlap 的块尝试并回前一块（合并后不超过 maxLength），
     * 放不下则保留 —— 避免单独成块污染 topK，同时绝不突破长度上限。
     */
    private List<String> mergeTinyChunks(List<String> chunks, int maxLength, int minChunkLength) {
        if (minChunkLength <= 0) return chunks;
        List<String> merged = new ArrayList<>();
        for (String chunk : chunks) {
            if (!merged.isEmpty() && chunk.length() < minChunkLength) {
                String prev = merged.get(merged.size() - 1);
                if (prev.length() + 1 + chunk.length() <= maxLength) {
                    merged.set(merged.size() - 1, prev + "\n" + chunk);
                    continue;
                }
            }
            merged.add(chunk);
        }
        return merged;
    }

    private static String last(String text, int n) {
        if (n <= 0) return "";
        return text.length() <= n ? text : text.substring(text.length() - n);
    }
}
