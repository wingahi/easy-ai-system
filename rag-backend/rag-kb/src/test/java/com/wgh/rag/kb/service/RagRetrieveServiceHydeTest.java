package com.wgh.rag.kb.service;

import com.wgh.rag.common.metrics.RagMetrics;
import com.wgh.rag.config.property.RagProperties;
import com.wgh.rag.kb.entity.KnowledgeBase;
import com.wgh.rag.kb.mapper.KnowledgeBaseMapper;
import com.wgh.rag.kb.port.vector.VectorMatch;
import com.wgh.rag.kb.port.vector.VectorScoreType;
import com.wgh.rag.llm.factory.ModelCacheManager;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * HYDE 双路召回与检索参数默认值：
 * 原查询必须作为独立召回路径参与融合，扩展路为空时原查询结果不能丢；minScore 默认取 0.55。
 */
@DisplayName("RAG 检索 - HYDE 双路与参数默认值")
class RagRetrieveServiceHydeTest {

    private final KnowledgeBaseMapper kbMapper = Mockito.mock(KnowledgeBaseMapper.class);
    private final ModelCacheManager modelCacheManager = Mockito.mock(ModelCacheManager.class);
    private final VectorStoreService vectorStoreService = Mockito.mock(VectorStoreService.class);
    private final RerankService rerankService = Mockito.mock(RerankService.class);
    private final KeywordSearchService keywordSearchService = Mockito.mock(KeywordSearchService.class);
    private final QueryTransformService queryTransformService = Mockito.mock(QueryTransformService.class);

    private final EmbeddingModel embeddingModel = Mockito.mock(EmbeddingModel.class);

    private RagRetrieveService service;

    @BeforeEach
    void setUp() {
        RagProperties props = new RagProperties();
        props.getRetrieve().setTopK(5);
        props.getRetrieve().setMinScore(0.55);
        service = newService(props);

        when(embeddingModel.embed(anyString())).thenReturn(Response.from(new Embedding(new float[]{0.1f})));
    }

    @Test
    @DisplayName("HYDE：原查询与假设答案各走一路，融合结果同时包含两路召回")
    void hyde_searches_both_original_and_hypothetical_answer() {
        mockKb();
        when(modelCacheManager.getEmbeddingModel(7L)).thenReturn(embeddingModel);
        Mockito.doReturn(matches("orig-doc")).doReturn(matches("hyde-doc"))
                .when(vectorStoreService).search(eq("kb_a"), any(), anyInt(), anyDouble(), any());
        when(queryTransformService.transform("原查询", "HYDE", 9L))
                .thenReturn(new QueryTransformService.TransformResult(
                        List.of("原查询", "假设答案"), List.of("假设答案"), true, 12));

        RetrieveResult result = service.searchMultiple(List.of(1L), "原查询",
                new RetrievalOptions(null, false, "HYDE", 9L));

        verify(embeddingModel).embed("原查询");
        verify(embeddingModel).embed("假设答案");
        verify(vectorStoreService, times(2)).search(eq("kb_a"), any(), anyInt(), anyDouble(), any());
        assertThat(result.matches()).extracting(m -> m.embeddingId())
                .contains("orig-doc", "hyde-doc");
        assertThat(result.scoreType()).isEqualTo(RetrieveResult.ScoreType.RRF);
        assertThat(result.transformedQueries()).containsExactly("假设答案");
    }

    @Test
    @DisplayName("HYDE 扩展路无命中：原查询一路仍可独立召回")
    void original_query_path_survives_when_hyde_returns_nothing() {
        mockKb();
        when(modelCacheManager.getEmbeddingModel(7L)).thenReturn(embeddingModel);
        Mockito.doReturn(matches("orig-doc")).doReturn(List.of())
                .when(vectorStoreService).search(eq("kb_a"), any(), anyInt(), anyDouble(), any());
        when(queryTransformService.transform("原查询", "HYDE", 9L))
                .thenReturn(new QueryTransformService.TransformResult(
                        List.of("原查询", "假设答案"), List.of("假设答案"), true, 12));

        RetrieveResult result = service.searchMultiple(List.of(1L), "原查询",
                new RetrievalOptions(null, false, "HYDE", 9L));

        assertThat(result.matches()).extracting(m -> m.embeddingId())
                .containsExactly("orig-doc");
    }

    @Test
    @DisplayName("minScore 未显式配置时，向量库请求使用默认 0.55")
    void default_min_score_is_0_55() {
        RagRetrieveService defaultService = newService(new RagProperties());
        mockKb();
        when(modelCacheManager.getEmbeddingModel(7L)).thenReturn(embeddingModel);
        Mockito.doReturn(List.of()).when(vectorStoreService)
                .search(eq("kb_a"), any(), anyInt(), anyDouble(), any());

        defaultService.searchMultiple(List.of(1L), "q", RetrievalOptions.defaults());

        verify(vectorStoreService).search(eq("kb_a"), any(), eq(5), eq(0.55), any());
    }

    // ===== 辅助 =====

    private RagRetrieveService newService(RagProperties props) {
        return new RagRetrieveService(kbMapper, modelCacheManager, vectorStoreService, props,
                rerankService, keywordSearchService, queryTransformService, Mockito.mock(RagMetrics.class));
    }

    private void mockKb() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(1L);
        kb.setCollectionName("kb_a");
        kb.setEmbeddingModelId(7L);
        kb.setDimension(1536);
        when(kbMapper.selectById(1L)).thenReturn(kb);
    }

    private List<VectorMatch> matches(String... ids) {
        List<VectorMatch> list = new ArrayList<>();
        for (int i = 0; i < ids.length; i++) {
            list.add(new VectorMatch(ids[i], 0.9 - i * 0.01, VectorScoreType.COSINE,
                    "chunk-" + ids[i], Map.of()));
        }
        return list;
    }
}
