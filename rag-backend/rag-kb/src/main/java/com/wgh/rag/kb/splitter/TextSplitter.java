package com.wgh.rag.kb.splitter;

import com.wgh.rag.config.property.RagProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 文本分割器门面 - 支持章节/段落/长度/自定义分割
 * 策略优先级由 {@link ChunkingStrategy#priority()} 声明：章节=1 段落=2 长度=3
 */
@Slf4j
@Component
public class TextSplitter {

    /**
     * 分割策略
     */
    public enum Strategy {
        AUTO,        // 按文档类型自动路由（FAQ 密度/章节密度检测，默认走 RECURSIVE）
        RECURSIVE,   // 递归字符分块（默认基线）：\n\n → \n → 句 → 子句 → 字
        CHAPTER,     // 按章节（识别章节标题：1. / 1.1 / # / ## 等）
        PARAGRAPH,   // 按段落
        FAQ,         // 问答对分割（识别「问N：...答：...」，一对一 chunk）
        LENGTH,      // 按长度
        CUSTOM       // 自定义（通过 config 传递）
    }

    private final Map<Strategy, ChunkingStrategy> strategies;

    /**
     * Spring 注入：按 priority 升序注册所有切分策略
     */
    @Autowired
    public TextSplitter(List<ChunkingStrategy> strategies) {
        this.strategies = indexByType(strategies);
    }

    /**
     * 兼容构造：手工组装默认策略链（不依赖 Spring 容器）
     */
    public TextSplitter(RagProperties ragProperties) {
        this(defaultStrategies(ragProperties));
    }

    private static List<ChunkingStrategy> defaultStrategies(RagProperties ragProperties) {
        RecursiveChunkingStrategy recursive = new RecursiveChunkingStrategy(ragProperties);
        LengthChunkingStrategy length = new LengthChunkingStrategy(ragProperties);
        ParagraphChunkingStrategy paragraph = new ParagraphChunkingStrategy(ragProperties, length);
        return List.of(
                new ChapterChunkingStrategy(paragraph),
                paragraph,
                length,
                new FaqChunkingStrategy(ragProperties, recursive),
                recursive,
                new CustomChunkingStrategy(paragraph));
    }

    private static Map<Strategy, ChunkingStrategy> indexByType(List<ChunkingStrategy> list) {
        Map<Strategy, ChunkingStrategy> map = new EnumMap<>(Strategy.class);
        list.stream()
                .sorted(Comparator.comparingInt(ChunkingStrategy::priority))
                .forEach(s -> map.put(s.type(), s));
        return map;
    }

    /**
     * 分割文本
     * @param originalParagraphs 解析后的原始段落
     * @param strategy 分割策略
     * @param customConfig 自定义配置（JSON 字符串，可选）
     */
    public List<String> split(List<String> originalParagraphs, Strategy strategy, String customConfig) {
        if (strategy == Strategy.AUTO) {
            strategy = routeByContent(originalParagraphs);
            log.info("AUTO 策略路由结果: {}", strategy);
        }
        ChunkingStrategy chunking = strategies.get(strategy);
        if (chunking == null) {
            throw new IllegalStateException("未注册的分割策略: " + strategy);
        }
        return chunking.split(originalParagraphs, customConfig);
    }

    // ============ AUTO：按文档类型路由 ============

    /**
     * 按内容特征自动选择分割策略：
     * - FAQ 模式密度高（问N：/Q：开头占比 >15%）→ FAQ
     * - 章节标题占比高（>10%）→ CHAPTER
     * - 默认 → RECURSIVE（递归字符分块基线）
     */
    private Strategy routeByContent(List<String> paragraphs) {
        if (paragraphs == null || paragraphs.isEmpty()) return Strategy.RECURSIVE;
        // FAQ 检测：全文本统计「问N：」行首模式出现次数（PDF 解析后段落边界不可靠，用全文计数更鲁棒）
        String fullText = String.join("\n", paragraphs);
        long faqCount = java.util.regex.Pattern.compile("(?m)^\\s*(问\\s*\\d+[：:]|Q\\d+[：:])")
                .matcher(fullText).results().count();
        if (faqCount >= 10) return Strategy.FAQ;
        // 章节检测：段落开头为章节标题的占比（用 isChapterTitle 严格判断）
        ChapterChunkingStrategy chapter = (ChapterChunkingStrategy) strategies.get(Strategy.CHAPTER);
        int total = paragraphs.size();
        long chapterLike = paragraphs.stream().filter(chapter::isChapterTitle).count();
        if (chapterLike * 100 / total > 10) return Strategy.CHAPTER;
        return Strategy.RECURSIVE;
    }
}
