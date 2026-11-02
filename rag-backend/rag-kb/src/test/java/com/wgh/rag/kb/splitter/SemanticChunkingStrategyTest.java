package com.wgh.rag.kb.splitter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.config.property.RagProperties;
import com.wgh.rag.llm.factory.ModelCacheManager;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * SemanticChunkingStrategy 单测：语义断块、阈值配置、降级路径
 */
@DisplayName("语义切分策略测试")
class SemanticChunkingStrategyTest {

    private RagProperties props;
    private ModelCacheManager modelCacheManager;
    private EmbeddingModel embeddingModel;
    private ParagraphChunkingStrategy paragraphStrategy;
    private SemanticChunkingStrategy strategy;

    @BeforeEach
    void setUp() {
        props = new RagProperties();
        props.getSplit().setMaxLength(512);
        props.getSplit().setOverlapLength(10);
        LengthChunkingStrategy lengthStrategy = new LengthChunkingStrategy(props);
        paragraphStrategy = new ParagraphChunkingStrategy(props, lengthStrategy);
        modelCacheManager = Mockito.mock(ModelCacheManager.class);
        embeddingModel = Mockito.mock(EmbeddingModel.class);
        strategy = new SemanticChunkingStrategy(props, modelCacheManager, paragraphStrategy, lengthStrategy, new ObjectMapper());
    }

    /** 按句内容定向返回向量：含"猫"→[1,0]，否则→[0,1] */
    private void stubEmbeddingsByContent() {
        when(embeddingModel.embedAll(anyList())).thenAnswer(inv -> {
            List<TextSegment> segs = inv.getArgument(0);
            List<Embedding> vectors = segs.stream()
                    .map(s -> s.text().contains("猫")
                            ? Embedding.from(new float[]{1f, 0f})
                            : Embedding.from(new float[]{0f, 1f}))
                    .toList();
            return Response.from(vectors);
        });
    }

    /** 话题转折处（相邻句余弦相似度 < 阈值）断块，块序号递增写入 metadata。 */
    @Test
    @DisplayName("SEMANTIC - 相邻句相似度低于阈值处断块，semanticBlockIndex 递增")
    void breaksAtTopicShift() {
        when(modelCacheManager.getEmbeddingModel(3L)).thenReturn(embeddingModel);
        stubEmbeddingsByContent();
        List<String> paras = List.of("猫喜欢吃鱼。猫是夜行动物。量子计算利用叠加态。量子比特可以纠缠。");

        List<ChunkOutput> result = strategy.splitDetailed(paras, null, new SplitContext(3L));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).text()).contains("猫喜欢吃鱼").contains("猫是夜行动物");
        assertThat(result.get(1).text()).contains("量子计算").contains("量子比特");
        assertThat(result.get(0).attributes().get("semanticBlockIndex")).isEqualTo(0);
        assertThat(result.get(1).attributes().get("semanticBlockIndex")).isEqualTo(1);
    }

    /** 全部句子相似（同话题）时合并为一个块。 */
    @Test
    @DisplayName("SEMANTIC - 相似句合并为单块")
    void similarSentencesMergeIntoOneBlock() {
        when(modelCacheManager.getEmbeddingModel(3L)).thenReturn(embeddingModel);
        when(embeddingModel.embedAll(anyList())).thenAnswer(inv -> {
            List<TextSegment> segs = inv.getArgument(0);
            return Response.from(segs.stream().map(s -> Embedding.from(new float[]{1f, 0f})).toList());
        });
        List<String> paras = List.of("猫喜欢吃鱼。猫喜欢晒太阳。猫喜欢睡觉。");

        List<ChunkOutput> result = strategy.splitDetailed(paras, null, new SplitContext(3L));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).attributes().get("semanticBlockIndex")).isEqualTo(0);
    }

    /** splitConfig 阈值生效：阈值 1.0 时非完全一致即断块。 */
    @Test
    @DisplayName("SEMANTIC - splitConfig 阈值可调")
    void thresholdConfigurable() {
        when(modelCacheManager.getEmbeddingModel(3L)).thenReturn(embeddingModel);
        // 每句向量都略有差异（索引混入），阈值 1.0 下逐句断块
        when(embeddingModel.embedAll(anyList())).thenAnswer(inv -> {
            List<TextSegment> segs = inv.getArgument(0);
            List<Embedding> vectors = new java.util.ArrayList<>();
            for (int i = 0; i < segs.size(); i++) {
                vectors.add(Embedding.from(new float[]{1f, 0.01f * (i + 1)}));
            }
            return Response.from(vectors);
        });
        List<String> paras = List.of("句子一。句子二。句子三。");

        List<ChunkOutput> result = strategy.splitDetailed(paras, "{\"threshold\": 1.0}", new SplitContext(3L));

        assertThat(result).hasSize(3);
        assertThat(result.stream().map(c -> c.attributes().get("semanticBlockIndex")))
                .containsExactly(0, 1, 2);
    }

    /** embedding 模型不可用（返回 null）时降级 PARAGRAPH，不携带 semanticBlockIndex。 */
    @Test
    @DisplayName("SEMANTIC - 模型不可用降级 PARAGRAPH")
    void fallsBackWhenModelMissing() {
        when(modelCacheManager.getEmbeddingModel(anyLong())).thenReturn(null);
        List<String> paras = List.of("猫喜欢吃鱼。猫是夜行动物。量子计算利用叠加态。量子比特可以纠缠。");

        List<ChunkOutput> result = strategy.splitDetailed(paras, null, new SplitContext(3L));

        List<String> expected = paragraphStrategy.split(paras, null);
        assertThat(result.stream().map(ChunkOutput::text)).isEqualTo(expected);
        assertThat(result).allSatisfy(c -> assertThat(c.attributes()).isEmpty());
    }

    /** embedding 调用抛异常时降级 PARAGRAPH，不阻断切分。 */
    @Test
    @DisplayName("SEMANTIC - embedding 异常降级 PARAGRAPH")
    void fallsBackWhenEmbeddingFails() {
        when(modelCacheManager.getEmbeddingModel(3L)).thenReturn(embeddingModel);
        when(embeddingModel.embedAll(anyList())).thenThrow(new RuntimeException("embedding api 401"));
        List<String> paras = List.of("猫喜欢吃鱼。猫是夜行动物。");

        List<ChunkOutput> result = strategy.splitDetailed(paras, null, new SplitContext(3L));

        List<String> expected = paragraphStrategy.split(paras, null);
        assertThat(result.stream().map(ChunkOutput::text)).isEqualTo(expected);
        assertThat(result).allSatisfy(c -> assertThat(c.attributes()).isEmpty());
    }

    /** 无切分上下文（split() 旧接口）时直接降级，不触碰 embedding 模型。 */
    @Test
    @DisplayName("SEMANTIC - 无上下文时 split() 降级 PARAGRAPH")
    void splitWithoutContextFallsBack() {
        List<String> paras = List.of("猫喜欢吃鱼。猫是夜行动物。");

        List<String> result = strategy.split(paras, null);

        assertThat(result).isEqualTo(paragraphStrategy.split(paras, null));
        Mockito.verifyNoInteractions(modelCacheManager);
    }

    /** 数字中的小数点/章节号（0.55、1.1）不被当作句尾切开。 */
    @Test
    @DisplayName("SEMANTIC - 小数与编号中的 . 不断句")
    void decimalDotsDoNotBreakSentences() {
        when(modelCacheManager.getEmbeddingModel(3L)).thenReturn(embeddingModel);
        when(embeddingModel.embedAll(anyList())).thenAnswer(inv -> {
            List<TextSegment> segs = inv.getArgument(0);
            return Response.from(segs.stream().map(s -> Embedding.from(new float[]{1f, 0f})).toList());
        });
        List<String> paras = List.of("## 1.1 核心能力\nminScore 默认值为 0.55 时可过滤噪声。");

        List<ChunkOutput> result = strategy.splitDetailed(paras, null, new SplitContext(3L));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).text()).contains("## 1.1 核心能力").contains("0.55");
    }

    /** 超长单句回退按长度切分，切分块共享同一 semanticBlockIndex。 */
    @Test
    @DisplayName("SEMANTIC - 超长句回退长度切分且共享块序号")
    void oversizedSentenceSplitByLength() {
        props.getSplit().setMaxLength(20);
        when(modelCacheManager.getEmbeddingModel(3L)).thenReturn(embeddingModel);
        stubEmbeddingsByContent();
        String longSentence = "这是一句没有标点且特别长的句子用来触发按长度切分的回退逻辑处理路径。";
        // 两句同话题（向量相同，不断块）：验证同一块内超长句长度切分后仍共享块序号
        List<String> paras = List.of(longSentence + "鱼短句。");

        List<ChunkOutput> result = strategy.splitDetailed(paras, null, new SplitContext(3L));

        assertThat(result.size()).isGreaterThan(1);
        assertThat(result).allSatisfy(c -> {
            assertThat(c.text().length()).isLessThanOrEqualTo(20);
            assertThat(c.attributes().get("semanticBlockIndex")).isEqualTo(0);
        });
    }
}
