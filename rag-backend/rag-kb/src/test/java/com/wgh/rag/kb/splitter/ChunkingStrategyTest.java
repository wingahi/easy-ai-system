package com.wgh.rag.kb.splitter;

import com.wgh.rag.config.property.RagProperties;
import com.wgh.rag.kb.splitter.TextSplitter.Strategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ChunkingStrategy 策略优先级与注册分发测试
 */
@DisplayName("切分策略优先级测试")
class ChunkingStrategyTest {

    private RagProperties props() {
        RagProperties props = new RagProperties();
        props.getSplit().setMaxLength(100);
        props.getSplit().setOverlapLength(10);
        return props;
    }

    private List<ChunkingStrategy> allStrategies(RagProperties props) {
        RecursiveChunkingStrategy recursive = new RecursiveChunkingStrategy(props);
        LengthChunkingStrategy length = new LengthChunkingStrategy(props);
        ParagraphChunkingStrategy paragraph = new ParagraphChunkingStrategy(props, length);
        return List.of(
                new ChapterChunkingStrategy(paragraph),
                paragraph,
                length,
                new FaqChunkingStrategy(props, recursive),
                recursive,
                new CustomChunkingStrategy(paragraph));
    }

    /** 章节、段落、长度策略的优先级依次为 1、2、3，排序后类型顺序固定。 */
    @Test
    @DisplayName("优先级 - 章节=1 段落=2 长度=3，替代硬编码顺序")
    void priorityOrder() {
        List<ChunkingStrategy> strategies = allStrategies(props());

        assertThat(strategies.stream().filter(s -> s.type() == Strategy.CHAPTER).findFirst().orElseThrow().priority()).isEqualTo(1);
        assertThat(strategies.stream().filter(s -> s.type() == Strategy.PARAGRAPH).findFirst().orElseThrow().priority()).isEqualTo(2);
        assertThat(strategies.stream().filter(s -> s.type() == Strategy.LENGTH).findFirst().orElseThrow().priority()).isEqualTo(3);

        List<ChunkingStrategy> sorted = strategies.stream()
                .sorted(Comparator.comparingInt(ChunkingStrategy::priority))
                .toList();
        assertThat(sorted.stream().map(ChunkingStrategy::type))
                .containsExactly(Strategy.CHAPTER, Strategy.PARAGRAPH, Strategy.LENGTH,
                        Strategy.FAQ, Strategy.RECURSIVE, Strategy.CUSTOM);
    }

    /** 每个策略类型恰有一个实现，注入顺序颠倒后各策略切分结果不变。 */
    @Test
    @DisplayName("注册分发 - 每个策略类型恰有一个实现，乱序注入行为一致")
    void dispatchIndependentOfInjectionOrder() {
        RagProperties props = props();
        List<ChunkingStrategy> strategies = allStrategies(props);
        List<ChunkingStrategy> reversed = new ArrayList<>(strategies).reversed();

        TextSplitter normal = new TextSplitter(strategies);
        TextSplitter shuffled = new TextSplitter(reversed);

        List<String> paras = List.of(
                "第一章 项目概述",
                "介绍项目的背景和目标。",
                "第二章 需求分析",
                "分析客户的具体需求点。"
        );
        for (Strategy s : Strategy.values()) {
            assertThat(shuffled.split(paras, s, null))
                    .as("策略 %s 乱序注入结果一致", s)
                    .isEqualTo(normal.split(paras, s, null));
        }
    }

    /** 六个策略实现覆盖除 AUTO 外的全部策略类型。 */
    @Test
    @DisplayName("注册分发 - 六个策略实现覆盖全部非 AUTO 类型")
    void allNonAutoTypesCovered() {
        List<ChunkingStrategy> strategies = allStrategies(props());

        assertThat(strategies).hasSize(6);
        assertThat(strategies.stream().map(ChunkingStrategy::type))
                .containsExactlyInAnyOrder(Strategy.CHAPTER, Strategy.PARAGRAPH, Strategy.LENGTH,
                        Strategy.FAQ, Strategy.RECURSIVE, Strategy.CUSTOM);
    }
}
