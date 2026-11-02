package com.wgh.rag.kb.splitter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.config.property.RagProperties;
import com.wgh.rag.llm.factory.ModelCacheManager;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SEMANTIC：语义切分 - 按句切分后，相邻句 embedding 余弦相似度低于阈值处断块，
 * 块内合并至 maxLength；使用知识库绑定的 embedding 模型（{@link SplitContext} 传入）。
 * 阈值可用 splitConfig JSON 覆盖：{"threshold": 0.5}。
 * 模型不可用 / embedding 失败时降级为 PARAGRAPH 切分，不阻断入库。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SemanticChunkingStrategy implements ChunkingStrategy {

    /** metadata key：语义块序号（同一块内因 maxLength 再分的 chunk 共享同一序号） */
    public static final String ATTR_SEMANTIC_BLOCK_INDEX = "semanticBlockIndex";
    private static final double DEFAULT_THRESHOLD = 0.5;
    /** 句读字符：中文句读总是断句；英文 . 仅在非数字之间才算句尾（避免 1.1 / 0.55 被切开） */
    private static final String CN_TERMINATORS = "。！？!?";

    private final RagProperties ragProperties;
    private final ModelCacheManager modelCacheManager;
    private final ParagraphChunkingStrategy paragraphStrategy;
    private final LengthChunkingStrategy lengthStrategy;
    private final ObjectMapper objectMapper;

    @Override
    public TextSplitter.Strategy type() {
        return TextSplitter.Strategy.SEMANTIC;
    }

    @Override
    public int priority() {
        return 8;
    }

    /** 无切分上下文（无 embedding 模型）时退化为段落切分 */
    @Override
    public List<String> split(List<String> paragraphs, String customConfig) {
        return splitDetailed(paragraphs, customConfig, null).stream().map(ChunkOutput::text).toList();
    }

    @Override
    public List<ChunkOutput> splitDetailed(List<String> paragraphs, String customConfig, SplitContext context) {
        double threshold = parseThreshold(customConfig);
        int maxLength = ragProperties.getSplit().getMaxLength();

        List<String> sentences = splitSentences(String.join("\n", paragraphs));
        if (sentences.isEmpty()) return List.of();
        if (sentences.size() == 1) {
            return mergeSentence(sentences.get(0), 0, maxLength);
        }

        EmbeddingModel embeddingModel = resolveEmbeddingModel(context);
        if (embeddingModel == null) {
            return fallback(paragraphs, customConfig, context, "embedding 模型不可用");
        }
        List<Embedding> embeddings;
        try {
            embeddings = embeddingModel.embedAll(
                    sentences.stream().map(TextSegment::from).toList()).content();
        } catch (Exception e) {
            return fallback(paragraphs, customConfig, context, "embedding 调用失败: " + e.getMessage());
        }
        if (embeddings == null || embeddings.size() != sentences.size()) {
            return fallback(paragraphs, customConfig, context, "embedding 返回数量与句数不一致");
        }

        // 相邻句相似度 < 阈值 → 断块；块内按 maxLength 合并
        List<ChunkOutput> out = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        int blockIndex = 0;
        for (int i = 0; i < sentences.size(); i++) {
            String sentence = sentences.get(i);
            if (sentence.length() > maxLength) {
                flush(out, buf, blockIndex);
                out.addAll(mergeSentence(sentence, blockIndex, maxLength));
            } else {
                if (buf.length() > 0 && buf.length() + sentence.length() + 1 > maxLength) {
                    flush(out, buf, blockIndex);
                }
                if (buf.length() > 0) buf.append("\n");
                buf.append(sentence);
            }
            if (i < sentences.size() - 1
                    && cosine(embeddings.get(i).vector(), embeddings.get(i + 1).vector()) < threshold) {
                flush(out, buf, blockIndex);
                blockIndex++;
            }
        }
        flush(out, buf, blockIndex);
        log.info("SEMANTIC 切分完成: {} 句 → {} 个语义块 / {} 个 chunk, 阈值={}", sentences.size(), blockIndex + 1, out.size(), threshold);
        return out;
    }

    /** 单句（或超长句）转 chunk：超 maxLength 回退按长度切分，共享同一 blockIndex */
    private List<ChunkOutput> mergeSentence(String sentence, int blockIndex, int maxLength) {
        if (sentence.length() <= maxLength) {
            return List.of(new ChunkOutput(sentence, Map.of(ATTR_SEMANTIC_BLOCK_INDEX, blockIndex)));
        }
        return lengthStrategy.splitByLength(sentence).stream()
                .map(piece -> new ChunkOutput(piece, Map.<String, Object>of(ATTR_SEMANTIC_BLOCK_INDEX, blockIndex)))
                .toList();
    }

    private void flush(List<ChunkOutput> out, StringBuilder buf, int blockIndex) {
        if (buf.length() == 0) return;
        out.add(new ChunkOutput(buf.toString(), Map.of(ATTR_SEMANTIC_BLOCK_INDEX, blockIndex)));
        buf.setLength(0);
    }

    private EmbeddingModel resolveEmbeddingModel(SplitContext context) {
        if (context == null || context.embeddingModelId() == null) return null;
        try {
            return modelCacheManager.getEmbeddingModel(context.embeddingModelId());
        } catch (Exception e) {
            log.warn("SEMANTIC 获取 embedding 模型失败 (id={}): {}", context.embeddingModelId(), e.getMessage());
            return null;
        }
    }

    /** 降级为 PARAGRAPH 切分（不带 semanticBlockIndex） */
    private List<ChunkOutput> fallback(List<String> paragraphs, String customConfig, SplitContext context, String reason) {
        log.warn("SEMANTIC 切分降级为 PARAGRAPH: {}", reason);
        return paragraphStrategy.splitDetailed(paragraphs, customConfig, context);
    }

    /** 阈值解析：splitConfig JSON {"threshold": 0.5}，非法/缺失时用默认值 */
    private double parseThreshold(String customConfig) {
        if (customConfig == null || customConfig.isBlank()) return DEFAULT_THRESHOLD;
        try {
            double t = objectMapper.readTree(customConfig).path("threshold").asDouble(DEFAULT_THRESHOLD);
            return (t >= -1.0 && t <= 1.0) ? t : DEFAULT_THRESHOLD;
        } catch (Exception e) {
            log.warn("SEMANTIC splitConfig 解析失败，使用默认阈值 {}: {}", DEFAULT_THRESHOLD, e.getMessage());
            return DEFAULT_THRESHOLD;
        }
    }

    /** 逐字符断句：换行为硬边界；. 前后任一侧是数字则视为小数点/编号，不断句 */
    private List<String> splitSentences(String text) {
        List<String> sentences = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                if (!cur.toString().isBlank()) sentences.add(cur.toString().trim());
                cur.setLength(0);
                continue;
            }
            cur.append(c);
            boolean terminator = CN_TERMINATORS.indexOf(c) >= 0;
            if (c == '.') {
                boolean prevDigit = i > 0 && Character.isDigit(text.charAt(i - 1));
                boolean nextDigit = i + 1 < text.length() && Character.isDigit(text.charAt(i + 1));
                terminator = !prevDigit && !nextDigit;
            }
            if (terminator && !cur.toString().isBlank()) {
                sentences.add(cur.toString().trim());
                cur.setLength(0);
            }
        }
        if (!cur.toString().isBlank()) sentences.add(cur.toString().trim());
        return sentences;
    }

    private double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return (na == 0 || nb == 0) ? 0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
