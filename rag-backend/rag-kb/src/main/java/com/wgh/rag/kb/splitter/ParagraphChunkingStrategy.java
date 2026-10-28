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
        List<String> result = new ArrayList<>();
        int maxLength = ragProperties.getSplit().getMaxLength();
        int overlap = ragProperties.getSplit().getOverlapLength();

        StringBuilder buf = new StringBuilder();
        for (String para : paragraphs) {
            if (para == null || para.isBlank()) continue;
            // 段落本身就超长 → flush 缓冲 + 段落内滑窗
            if (para.length() > maxLength) {
                if (buf.length() > 0) {
                    result.add(buf.toString());
                    buf.setLength(0);
                }
                result.addAll(lengthStrategy.splitByLength(para));
                continue;
            }
            // 加上这段会超 → flush，从 buf 末尾保留 overlap 作为下一段开头
            if (buf.length() + para.length() + 1 > maxLength) {
                String tail = buf.length() > overlap
                        ? buf.substring(buf.length() - overlap)
                        : buf.toString();
                result.add(buf.toString());
                buf = new StringBuilder(tail);
            }
            if (buf.length() > 0 && buf.charAt(buf.length() - 1) != '\n') buf.append("\n");
            buf.append(para);
        }
        if (buf.length() > 0) result.add(buf.toString());
        return result;
    }
}
