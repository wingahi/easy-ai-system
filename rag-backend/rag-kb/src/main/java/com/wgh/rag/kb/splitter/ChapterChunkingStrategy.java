package com.wgh.rag.kb.splitter;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * CHAPTER：按章节分割 - 识别章节标题；
 * 切分失败（只切出 0/1 块且原文多段）时回退段落分割
 */
@Component
@RequiredArgsConstructor
public class ChapterChunkingStrategy implements ChunkingStrategy {

    private final ParagraphChunkingStrategy paragraphStrategy;

    @Override
    public TextSplitter.Strategy type() {
        return TextSplitter.Strategy.CHAPTER;
    }

    @Override
    public int priority() {
        return 1;
    }

    /**
     * 按章节分割 - 识别章节标题
     * <p>
     * 正则收敛原则（避免正文中的"第一章..."被误判）：
     * 1. 数字标题：^1. / ^1.1. / 1、 等 —— 整行 ≤ 40 字，且不是以正文助词结尾
     * 2. Markdown 标题：^# / ^## —— 天然是标题
     * 3. 中文章节：第X章 / 第X节 / 第X篇 —— 整行 ≤ 40 字，且章节词后紧跟空格/制表符/换行（排除"第一章的..."这种正文）
     */
    @Override
    public List<String> split(List<String> paragraphs, String customConfig) {
        List<String> result = new ArrayList<>();
        StringBuilder currentChapter = new StringBuilder();

        for (String para : paragraphs) {
            boolean isChapterTitle = isChapterTitle(para);

            if (isChapterTitle && currentChapter.length() > 0) {
                result.add(currentChapter.toString().trim());
                currentChapter = new StringBuilder();
            }
            if (currentChapter.length() > 0) {
                currentChapter.append("\n");
            }
            currentChapter.append(para);
        }

        if (currentChapter.length() > 0) {
            result.add(currentChapter.toString().trim());
        }

        // 如果没识别出章节，回退到段落分割
        if (shouldFallbackToParagraph(paragraphs, result)) {
            return paragraphStrategy.split(paragraphs, customConfig);
        }

        return result;
    }

    /** 章节标题严格识别 —— 三种模式 + 长度/助词约束 */
    public boolean isChapterTitle(String line) {
        if (line == null) return false;
        String trimmed = line.trim();
        // Markdown 标题（天然安全）
        if (trimmed.matches("^#{1,4}\\s+.+")) return true;
        // 数字标题：1. / 1.1 / 1、 / 1.、 等 —— 整行 ≤ 40 字
        if (trimmed.matches("^\\s*\\d+([.、]\\s*\\d*)*[.、]?\\s*.+") && trimmed.length() <= 40) {
            return !endsWithSentenceParticle(trimmed);
        }
        // 中文章节：第X章 / 第X节 / 第X篇 —— 整行 ≤ 40 字，且章节词后紧跟空格/冒号/换行（不是"第X章的..."正文）
        if (trimmed.matches("^\\s*第[一二三四五六七八九十百千\\d]+[章节篇部][\\s:：].+") && trimmed.length() <= 40) {
            return !endsWithSentenceParticle(trimmed);
        }
        return false;
    }

    /** 正文助词/语气词结尾 —— 标题不会以这些结尾 */
    private boolean endsWithSentenceParticle(String s) {
        String[] particles = {"的", "了", "等", "中", "里", "上", "下", "进行", "介绍", "包括", "聚焦", "关于", "相关"};
        for (String p : particles) {
            if (s.endsWith(p)) return true;
        }
        return false;
    }

    private boolean shouldFallbackToParagraph(List<String> paragraphs, List<String> chapters) {
        // 章节切分失败（只切出 0/1 块）且原文有多个段落 → 回退段落切分
        return chapters.size() <= 1 && paragraphs.size() > 1;
    }
}
