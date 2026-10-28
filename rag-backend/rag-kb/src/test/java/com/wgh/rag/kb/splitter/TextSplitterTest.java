package com.wgh.rag.kb.splitter;

import com.wgh.rag.config.property.RagProperties;
import com.wgh.rag.kb.splitter.TextSplitter.Strategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TextSplitter 行为测试（重构前补，重构后保持绿）
 * 覆盖：AUTO 路由 FAQ/CHAPTER/RECURSIVE、CHAPTER 回退 PARAGRAPH、
 * FAQ 回退 RECURSIVE、段落滑窗重叠、超长段落再切分、中文章节标题三约束
 */
@DisplayName("文本分割器行为测试")
class TextSplitterTest {

    private TextSplitter newSplitter(int maxLength, int overlap) {
        RagProperties props = new RagProperties();
        props.getSplit().setMaxLength(maxLength);
        props.getSplit().setOverlapLength(overlap);
        return new TextSplitter(props);
    }

    // ============ AUTO 路由 ============

    /** 段落中问N：问答模式达到 10 处以上时 AUTO 路由到 FAQ，每问一答合成一个 chunk。 */
    @Test
    @DisplayName("AUTO - 问N：模式 ≥10 时路由到 FAQ，一问一答一个 chunk")
    void autoRoutesToFaq() {
        TextSplitter splitter = newSplitter(512, 64);
        List<String> paras = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            paras.add("问" + i + "：如何配置知识库？");
            paras.add("答：在知识库页面进行操作。");
        }

        List<String> result = splitter.split(paras, Strategy.AUTO, null);

        assertThat(result).hasSize(12);
        assertThat(result.get(0)).startsWith("问1：").contains("答：");
        assertThat(result.get(11)).startsWith("问12：");
    }

    /** 章节标题占比超过 10% 时 AUTO 路由到 CHAPTER，按章节聚合正文。 */
    @Test
    @DisplayName("AUTO - 章节标题占比 >10% 时路由到 CHAPTER")
    void autoRoutesToChapter() {
        TextSplitter splitter = newSplitter(512, 64);
        List<String> paras = List.of(
                "第一章 项目概述",
                "介绍项目的背景和目标。",
                "第二章 需求分析",
                "分析客户的具体需求点。",
                "第三章 技术方案",
                "给出完整的技术实现方案。"
        );

        List<String> result = splitter.split(paras, Strategy.AUTO, null);

        assertThat(result).hasSize(3);
        assertThat(result.get(0)).contains("第一章 项目概述").contains("介绍项目的背景和目标。");
        assertThat(result.get(1)).contains("第二章 需求分析");
        assertThat(result.get(2)).contains("第三章 技术方案");
    }

    /** 无 FAQ 与章节特征时 AUTO 回退 RECURSIVE，结果与显式 RECURSIVE 一致。 */
    @Test
    @DisplayName("AUTO - 无 FAQ/章节特征时路由到 RECURSIVE（与显式 RECURSIVE 结果一致）")
    void autoRoutesToRecursiveByDefault() {
        TextSplitter splitter = newSplitter(50, 10);
        List<String> paras = List.of(
                "人工智能正在改变世界的方方面面。",
                "机器学习是人工智能的重要分支。",
                "深度学习推动了近年来的技术突破。"
        );

        List<String> auto = splitter.split(paras, Strategy.AUTO, null);
        List<String> recursive = splitter.split(paras, Strategy.RECURSIVE, null);

        assertThat(auto).isEqualTo(recursive);
    }

    /** 输入为空时 AUTO 仍返回空列表。 */
    @Test
    @DisplayName("AUTO - 空输入路由 RECURSIVE 并返回空列表")
    void autoEmptyInput() {
        TextSplitter splitter = newSplitter(512, 64);
        assertThat(splitter.split(List.of(), Strategy.AUTO, null)).isEmpty();
    }

    // ============ CHAPTER ============

    /** 多段普通文本中未识别出章节标题时 CHAPTER 回退为 PARAGRAPH 切分。 */
    @Test
    @DisplayName("CHAPTER - 未识别出章节且原文多段时回退 PARAGRAPH")
    void chapterFallsBackToParagraph() {
        TextSplitter splitter = newSplitter(60, 10);
        String para = "这是普通段落内容，没有任何章节标题特征，纯属正文叙述文字，长度凑足五十个字整。";
        List<String> paras = List.of(para, para, para);

        List<String> chapter = splitter.split(paras, Strategy.CHAPTER, null);
        List<String> paragraph = splitter.split(paras, Strategy.PARAGRAPH, null);

        assertThat(paragraph.size()).isGreaterThan(1);
        assertThat(chapter).isEqualTo(paragraph);
    }

    /** 只有单段且无标题时不回退，直接返回原段落。 */
    @Test
    @DisplayName("CHAPTER - 单段落无标题时不回退，返回原段")
    void chapterSingleParagraphNoFallback() {
        TextSplitter splitter = newSplitter(512, 64);
        List<String> result = splitter.split(List.of("只有一段普通文本，没有标题。"), Strategy.CHAPTER, null);

        assertThat(result).containsExactly("只有一段普通文本，没有标题。");
    }

    /** 中文章节标题需满足章节词后空格或冒号、整行不超过 40 字、不以助词结尾三个条件。 */
    @Test
    @DisplayName("CHAPTER - 中文章节标题三约束：章节词后须空格/冒号、整行≤40字、不以助词结尾")
    void chapterChineseTitleConstraints() {
        TextSplitter splitter = newSplitter(512, 64);
        String longTitle = "第四章 " + "标".repeat(40); // 整行 > 40 字 → 非标题
        List<String> paras = List.of(
                "第一章的详细介绍",          // 章节词后紧跟"的" → 非标题
                "正文A",
                "第二章 需求分析",           // 空格 → 标题
                "正文B",
                "第三章：技术方案",          // 冒号 → 标题
                "正文C",
                longTitle,                  // 超长 → 非标题
                "正文D",
                "第五章 方案介绍的",          // 助词结尾 → 非标题
                "正文E",
                "第六章 总结",               // 标题
                "正文F"
        );

        List<String> result = splitter.split(paras, Strategy.CHAPTER, null);

        assertThat(result).hasSize(4);
        assertThat(result.get(0)).startsWith("第一章的详细介绍");
        assertThat(result.get(1)).startsWith("第二章 需求分析");
        assertThat(result.get(2)).startsWith("第三章：技术方案")
                .contains("第四章")          // 超长标题被当作正文并入上一章
                .contains("第五章 方案介绍的"); // 助词结尾被当作正文
        assertThat(result.get(3)).startsWith("第六章 总结");
    }

    // ============ FAQ ============

    /** 未识别到问N：模式时 FAQ 回退 RECURSIVE，结果与显式 RECURSIVE 一致。 */
    @Test
    @DisplayName("FAQ - 识别失败（无问N模式）时回退 RECURSIVE")
    void faqFallsBackToRecursive() {
        TextSplitter splitter = newSplitter(50, 10);
        List<String> paras = List.of(
                "人工智能正在改变世界的方方面面。",
                "机器学习是人工智能的重要分支。",
                "深度学习推动了近年来的技术突破。"
        );

        List<String> faq = splitter.split(paras, Strategy.FAQ, null);
        List<String> recursive = splitter.split(paras, Strategy.RECURSIVE, null);

        assertThat(faq).isEqualTo(recursive);
    }

    // ============ PARAGRAPH ============

    /** PARAGRAPH 切分时后一个 chunk 以固定 overlap 长度的前文结尾开头。 */
    @Test
    @DisplayName("PARAGRAPH - 相邻 chunk 保留固定长度重叠（上一 chunk 末尾 overlap 字）")
    void paragraphSlidingWindowOverlap() {
        TextSplitter splitter = newSplitter(50, 10);
        String para1 = "甲".repeat(40);
        String para2 = "乙".repeat(40);

        List<String> result = splitter.split(List.of(para1, para2), Strategy.PARAGRAPH, null);

        assertThat(result).hasSize(2);
        assertThat(result.get(0)).isEqualTo(para1);
        // 第二 chunk 以第一 chunk 末尾 10 字（overlap）开头
        assertThat(result.get(1)).startsWith("甲".repeat(10));
        assertThat(result.get(1)).contains("乙".repeat(40));
    }

    /** 超过 maxLength 的段落会被再次切分，每个 chunk 长度均不超过上限。 */
    @Test
    @DisplayName("PARAGRAPH - 超长段落内部再切分，每块不超过 maxLength")
    void paragraphOversizedSplitByLength() {
        TextSplitter splitter = newSplitter(50, 5);
        String longPara = "测试句子。".repeat(30); // 150 字 > maxLength

        List<String> result = splitter.split(List.of(longPara), Strategy.PARAGRAPH, null);

        assertThat(result.size()).isGreaterThan(1);
        assertThat(result).allSatisfy(chunk -> assertThat(chunk.length()).isLessThanOrEqualTo(50));
    }

    // ============ RECURSIVE ============

    /** RECURSIVE 切分后相邻 chunk 按 overlap 重叠，且单块长度不超过 maxLength。 */
    @Test
    @DisplayName("RECURSIVE - 相邻 chunk 按 overlap 重叠且不超 maxLength")
    void recursiveAdjacentChunkOverlap() {
        TextSplitter splitter = newSplitter(30, 5);
        String text = "AAAA。BBBB。CCCC。DDDD。EEEE。FFFF。GGGG。"; // 35 字，句边界清晰

        List<String> result = splitter.split(List.of(text), Strategy.RECURSIVE, null);

        assertThat(result).hasSize(2);
        assertThat(result.get(0)).hasSize(30);
        // 第二 chunk = 第一 chunk 末尾 5 字（FFFF。）+ 剩余内容（GGGG。）
        assertThat(result.get(1)).isEqualTo("FFFF。GGGG。");
    }
}
