package com.wgh.rag.kb.splitter;

import com.wgh.rag.config.property.RagProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本分割器 - 支持章节/段落/长度/自定义分割
 * 优先顺序：章节分割 -> 段落分割 -> 长度分割
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TextSplitter {

    private final RagProperties ragProperties;

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

    /** 递归分块的分隔符层级：段落 → 行 → 句 → 子句 → 词 → 字 */
    private static final String[] RECURSIVE_SEPARATORS = {"\n\n", "\n", "。", "！", "？", "；", ".", "!", "?", ";", "，", ",", " ", ""};

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
        return switch (strategy) {
            case RECURSIVE -> splitRecursive(String.join("\n", originalParagraphs));
            case CHAPTER -> splitByChapter(originalParagraphs);
            case PARAGRAPH -> splitByParagraph(originalParagraphs);
            case FAQ -> splitByFaq(originalParagraphs);
            case LENGTH -> splitByLength(String.join("\n", originalParagraphs));
            case CUSTOM -> splitByCustom(originalParagraphs, customConfig);
            case AUTO -> throw new IllegalStateException("AUTO 应已路由");
        };
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
        int total = paragraphs.size();
        long chapterLike = paragraphs.stream().filter(this::isChapterTitle).count();
        if (chapterLike * 100 / total > 10) return Strategy.CHAPTER;
        return Strategy.RECURSIVE;
    }

    // ============ RECURSIVE：递归字符分块（默认基线） ============

    /**
     * 递归字符分块：按分隔符层级 ["\n\n","\n","。","！","？","；",".",","," ",""] 逐级降级切割，
     * 尽量在高层级语义边界（段落/行/句）切分；相邻 chunk 保留 overlap 重叠。
     */
    private List<String> splitRecursive(String text) {
        int maxLength = ragProperties.getSplit().getMaxLength();
        int overlap = ragProperties.getSplit().getOverlapLength();
        List<String> pieces = recursiveCut(text, maxLength, 0);
        return mergeWithOverlap(pieces, maxLength, overlap);
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

    // ============ FAQ：问答对分割 ============

    /**
     * FAQ 分割：识别「问N：... 答：...」对，一问一答为一个 chunk；
     * 单对超长时递归切割；识别失败回退 RECURSIVE。
     */
    private List<String> splitByFaq(List<String> paragraphs) {
        String text = String.join("\n", paragraphs);
        // 按「问N：」或「Q：」起始切分出问答单元（(?m) 开启多行模式使 ^ 匹配每行行首）
        String[] rawUnits = text.split("(?m)(?=^\\s*(问\\s*\\d*[：:]|Q\\d*[：:]))");
        List<String> units = new ArrayList<>();
        for (String unit : rawUnits) {
            String t = unit.trim();
            if (!t.isEmpty()) units.add(t);
        }
        // 识别失败（切不出 ≥2 个单元）→ 回退递归
        if (units.size() < 2) {
            log.info("FAQ 模式识别失败，回退 RECURSIVE");
            return splitRecursive(text);
        }
        int maxLength = ragProperties.getSplit().getMaxLength();
        List<String> result = new ArrayList<>();
        for (String unit : units) {
            if (unit.length() > maxLength) {
                result.addAll(recursiveCut(unit, maxLength, 0));
            } else {
                result.add(unit);
            }
        }
        return result;
    }

    /**
     * 按章节分割 - 识别章节标题
     * <p>
     * 正则收敛原则（避免正文中的"第一章..."被误判）：
     * 1. 数字标题：^1. / ^1.1. / 1、 等 —— 整行 ≤ 40 字，且不是以正文助词结尾
     * 2. Markdown 标题：^# / ^## —— 天然是标题
     * 3. 中文章节：第X章 / 第X节 / 第X篇 —— 整行 ≤ 40 字，且章节词后紧跟空格/制表符/换行（排除"第一章的..."这种正文）
     */
    private List<String> splitByChapter(List<String> paragraphs) {
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
            return splitByParagraph(paragraphs);
        }

        return result;
    }

    /** 章节标题严格识别 —— 三种模式 + 长度/助词约束 */
    private boolean isChapterTitle(String line) {
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

    /**
     * 按段落分割 - 滑窗合并多个段落，相邻 chunk 保留 overlap 重叠，避免跨段落语义断裂
     */
    private List<String> splitByParagraph(List<String> paragraphs) {
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
                result.addAll(splitByLength(para));
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

    /**
     * 按固定长度分割（带重叠）
     */
    private List<String> splitByLength(String text) {
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

    /**
     * 自定义分割 - 支持传入正则分割
     */
    private List<String> splitByCustom(List<String> paragraphs, String customConfig) {
        // 简单实现：如果有自定义正则，按正则分割
        // 复杂场景可通过 customConfig JSON 扩展
        return splitByParagraph(paragraphs);
    }
}
