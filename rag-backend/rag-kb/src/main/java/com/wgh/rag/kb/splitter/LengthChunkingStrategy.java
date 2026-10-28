package com.wgh.rag.kb.splitter;

import com.wgh.rag.config.property.RagProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * LENGTH：按固定长度分割（带重叠），尽量在句子边界切分
 */
@Component
@RequiredArgsConstructor
public class LengthChunkingStrategy implements ChunkingStrategy {

    private final RagProperties ragProperties;

    @Override
    public TextSplitter.Strategy type() {
        return TextSplitter.Strategy.LENGTH;
    }

    @Override
    public int priority() {
        return 3;
    }

    @Override
    public List<String> split(List<String> paragraphs, String customConfig) {
        return splitByLength(String.join("\n", paragraphs));
    }

    /**
     * 按固定长度分割（带重叠）
     */
    public List<String> splitByLength(String text) {
        List<String> result = new ArrayList<>();
        int maxLength = ragProperties.getSplit().getMaxLength();
        int overlap = ragProperties.getSplit().getOverlapLength();

        if (text.length() <= maxLength) {
            result.add(text);
            return result;
        }

        int start = 0;
        int safety = 0;
        int maxIterations = (text.length() / Math.max(1, maxLength - overlap)) + 1000;

        while (start < text.length() && safety++ < maxIterations) {
            int end = Math.min(start + maxLength, text.length());
            int originalEnd = end;
            String chunk = text.substring(start, end);

            // 尽量在句子边界切分（仅在 chunk 后半段找，避免切得太短）
            if (end < text.length()) {
                int halfLen = chunk.length() / 2;
                String tail = chunk.substring(halfLen);
                int tailEnd = findLastSentenceEnd(tail);
                if (tailEnd > 0) {
                    int sentenceEnd = halfLen + tailEnd;
                    chunk = chunk.substring(0, sentenceEnd);
                    end = start + sentenceEnd;
                }
            }

            String trimmed = chunk.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }

            // 前进量：有 overlap 但不能卡在原地
            int step = Math.max(1, end - overlap);
            if (step <= start) {
                // 句子边界没找到或者 overlap 配置不合法，强制前进至少 1 个字符避免死循环
                step = originalEnd;
            }
            start = step;
        }
        return result;
    }

    private int findLastSentenceEnd(String text) {
        char[] chars = {'。', '！', '？', '.', '!', '?', '\n'};
        int lastPos = -1;
        for (char c : chars) {
            int pos = text.lastIndexOf(c);
            if (pos > lastPos) {
                lastPos = pos;
            }
        }
        return lastPos + 1;
    }
}
