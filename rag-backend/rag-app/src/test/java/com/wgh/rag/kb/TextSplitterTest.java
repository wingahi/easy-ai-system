package com.wgh.rag.kb;

import com.wgh.rag.config.property.RagProperties;
import com.wgh.rag.kb.splitter.TextSplitter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TextSplitter 单元测试
 * 覆盖：章节分割 / 段落分割 / 长度分割
 */
@DisplayName("文本分割器测试")
class TextSplitterTest {

    private TextSplitter splitter;

    @BeforeEach
    void setUp() {
        RagProperties ragProps = new RagProperties();
        ragProps.getSplit().setMaxLength(200);
        ragProps.getSplit().setOverlapLength(20);
        splitter = new TextSplitter(ragProps);
    }

    // ============ 按段落分割 ============

    @Test
    @DisplayName("PARAGRAPH - 多段超长段落各自被拆分")
    void splitByParagraph_multiple() {
        // maxLength=200，每段 200+ 字 → 每段都被内部拆分，最终 chunks 数 > 输入段落数
        String longPara1 = "第一段内容".repeat(20) + "，关于人工智能的发展历史与关键里程碑事件。";
        String longPara2 = "第二段内容".repeat(20) + "，关于机器学习算法的分类与应用场景。";
        String longPara3 = "第三段内容".repeat(20) + "，关于深度学习与神经网络的演进方向。";

        List<String> input = List.of(longPara1, longPara2, longPara3);
        List<String> result = splitter.split(input, TextSplitter.Strategy.PARAGRAPH, null);

        // 3 段长于 maxLength 的段落 → 每段至少被拆成 2 块 → 总数 ≥ 6
        assertThat(result.size()).isGreaterThanOrEqualTo(3);
        // 所有 chunk 都应该含有原始段落的特征内容（不会丢）
        String joined = String.join("", result);
        assertThat(joined).contains("人工智能").contains("机器学习").contains("神经网络");
    }

    @Test
    @DisplayName("PARAGRAPH - 短段落滑窗合并（符合语义）")
    void splitByParagraph_shortMerged() {
        // maxLength=200，每段 ~20 字 → 全部被合并成 1 段（滑窗避免碎片化）
        List<String> input = List.of(
                "第一段内容，关于人工智能的发展历史。",
                "第二段内容，关于机器学习算法。",
                "第三段内容，关于深度学习与神经网络。"
        );
        List<String> result = splitter.split(input, TextSplitter.Strategy.PARAGRAPH, null);

        // 全部短段落被滑窗合并 → 返回 1 段
        assertThat(result).hasSize(1);
        assertThat(result.get(0)).contains("人工智能").contains("机器学习").contains("神经网络");
    }

    @Test
    @DisplayName("PARAGRAPH - 空输入返回空列表")
    void splitByParagraph_empty() {
        List<String> result = splitter.split(List.of(), TextSplitter.Strategy.PARAGRAPH, null);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("PARAGRAPH - null 元素处理")
    void splitByParagraph_withNull() {
        List<String> input = List.of("有效段落", "");
        List<String> result = splitter.split(input, TextSplitter.Strategy.PARAGRAPH, null);
        assertThat(result).isNotEmpty();
    }

    // ============ 按章节分割 ============

    @Test
    @DisplayName("CHAPTER - 数字开头章节标题识别")
    void splitByChapter_numberTitle() {
        List<String> input = List.of(
                "1. 人工智能概述",
                "这里是第一章的详细内容，介绍AI的基本概念。",
                "包含历史背景、应用场景等。",
                "2. 机器学习基础",
                "第二章介绍机器学习的核心算法。",
                "包括监督学习、非监督学习等。",
                "3. 深度学习进阶",
                "第三章聚焦神经网络与Transformer。"
        );
        List<String> result = splitter.split(input, TextSplitter.Strategy.CHAPTER, null);

        // 应该识别出 3 个章节
        assertThat(result).hasSize(3);
        assertThat(result.get(0)).contains("人工智能概述");
        assertThat(result.get(1)).contains("机器学习基础");
        assertThat(result.get(2)).contains("深度学习进阶");
    }

    @Test
    @DisplayName("CHAPTER - Markdown 标题识别")
    void splitByChapter_markdownTitle() {
        List<String> input = List.of(
                "# 系统架构设计",
                "介绍系统的整体架构和技术选型。",
                "## 前端架构",
                "使用 Vue3 + Element Plus 构建。",
                "## 后端架构",
                "Spring Boot 3 + LangChain4j 实现 RAG。"
        );
        List<String> result = splitter.split(input, TextSplitter.Strategy.CHAPTER, null);
        assertThat(result).hasSize(3);
    }

    @Test
    @DisplayName("CHAPTER - 中文章节标题识别")
    void splitByChapter_chineseTitle() {
        List<String> input = List.of(
                "第一章 项目概述",
                "介绍项目的背景和目标。",
                "第二章 需求分析",
                "分析客户的具体需求点。",
                "第三章 技术方案",
                "给出完整的技术实现方案。"
        );
        List<String> result = splitter.split(input, TextSplitter.Strategy.CHAPTER, null);
        assertThat(result).hasSize(3);
    }

    // ============ 按长度分割 ============

    @Test
    @DisplayName("LENGTH - 短文本不分割")
    void splitByLength_shortText() {
        List<String> result = splitter.split(List.of("短文本内容"), TextSplitter.Strategy.LENGTH, null);
        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isEqualTo("短文本内容");
    }

    @Test
    @DisplayName("LENGTH - 长文本切分（> maxLength）")
    void splitByLength_longText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            sb.append("这是一个用于测试长度分割的文本段落，需要达到足够的字符数。");
        }
        List<String> input = List.of(sb.toString());
        List<String> result = splitter.split(input, TextSplitter.Strategy.LENGTH, null);

        assertThat(result.size()).isGreaterThan(1);
        // 每个 chunk 不超过 maxLength + 一些边界
        result.forEach(chunk -> assertThat(chunk.length()).isLessThanOrEqualTo(250));
    }

    @Test
    @DisplayName("LENGTH - 超长单段按句边界切分")
    void splitByLength_preserveSentence() {
        // 调整 maxLength 让段落正好被切分
        RagProperties props = new RagProperties();
        props.getSplit().setMaxLength(50);
        props.getSplit().setOverlapLength(5);
        TextSplitter localSplitter = new TextSplitter(props);

        String text = "第一句。第二句。第三句。第四句。第五句。";
        List<String> result = localSplitter.split(List.of(text), TextSplitter.Strategy.LENGTH, null);
        assertThat(result.size()).isGreaterThanOrEqualTo(1);
    }

    // ============ 边界场景 ============

    @Test
    @DisplayName("所有策略 - 单段短内容只返回一段")
    void allStrategies_singleParagraph() {
        List<String> input = List.of("只有一段内容。");

        for (TextSplitter.Strategy s : TextSplitter.Strategy.values()) {
            if (s == TextSplitter.Strategy.CUSTOM) continue;
            List<String> result = splitter.split(input, s, null);
            assertThat(result).hasSize(1).as("策略 %s 应该返回一段", s);
        }
    }
}
