package com.wgh.rag.kb.service;

import com.wgh.rag.config.property.RagProperties;
import com.wgh.rag.kb.entity.KnowledgeBase;
import com.wgh.rag.kb.mapper.KnowledgeBaseMapper;
import com.wgh.rag.llm.factory.ModelCacheManager;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * RagRetrieveService Rerank 路径单测：候选放大、重排序、失败降级
 */
class RagRetrieveServiceRerankTest {

    private final KnowledgeBaseMapper kbMapper = Mockito.mock(KnowledgeBaseMapper.class);
    private final ModelCacheManager modelCacheManager = Mockito.mock(ModelCacheManager.class);
    private final VectorStoreService vectorStoreService = Mockito.mock(VectorStoreService.class);
    private final RerankService rerankService = Mockito.mock(RerankService.class);
    private final KeywordSearchService keywordSearchService = Mockito.mock(KeywordSearchService.class);
    private final QueryTransformService queryTransformService = Mockito.mock(QueryTransformService.class);
    private final EmbeddingModel embeddingModel = Mockito.mock(EmbeddingModel.class);
    private final PgVectorEmbeddingStore store = Mockito.mock(PgVectorEmbeddingStore.class);

    private RagRetrieveService service;

    @BeforeEach
    void setUp() {
        RagProperties props = new RagProperties();
        props.getRetrieve().setTopK(2);
        props.getRetrieve().setMinScore(0.0);
        service = new RagRetrieveService(kbMapper, modelCacheManager, vectorStoreService, props,
                rerankService, keywordSearchService, queryTransformService);

        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(1L);
        kb.setCollectionName("kb_1");
        kb.setEmbeddingModelId(7L);
        kb.setDimension(1536);
        when(kbMapper.selectById(1L)).thenReturn(kb);
        when(modelCacheManager.getEmbeddingModel(7L)).thenReturn(embeddingModel);
        when(embeddingModel.embed(anyString())).thenReturn(Response.from(new Embedding(new float[]{0.1f})));
        Mockito.doReturn(store).when(vectorStoreService).createEmbeddingStore(eq("kb_1"), anyInt());
    }

    private void mockVectorMatches(int count) {
        List<EmbeddingMatch<TextSegment>> matches = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            matches.add(new EmbeddingMatch<>(0.9 - i * 0.01, "id" + i, null,
                    TextSegment.from("chunk" + i)));
        }
        when(store.search(any())).thenReturn(new EmbeddingSearchResult<>(matches));
    }

    @Test
    void rerank_disabled_returns_vector_order_topK() {
        mockVectorMatches(5);
        RetrieveResult result = service.searchMultiple(List.of(1L), "q", RetrievalOptions.defaults());
        assertThat(result.rerankApplied()).isFalse();
        assertThat(result.matches()).hasSize(2);
        assertThat(result.matches().get(0).embedded().text()).isEqualTo("chunk0");
        // 单路向量召回：分数是余弦相似度，可与 minScore 比较
        assertThat(result.scoreType()).isEqualTo(RetrieveResult.ScoreType.COSINE);
    }

    @Test
    void rerank_enabled_reorders_by_rerank_scores_and_limits_topK() {
        mockVectorMatches(5);
        // rerank 将 chunk2 提到第一
        when(rerankService.scoreAll(anyList(), eq("q"), eq(99L)))
                .thenReturn(List.of(0.1, 0.2, 0.95, 0.3, 0.4));

        RetrieveResult result = service.searchMultiple(List.of(1L), "q", new RetrievalOptions(99L));

        assertThat(result.rerankApplied()).isTrue();
        assertThat(result.rerankLatencyMs()).isGreaterThanOrEqualTo(0);
        assertThat(result.matches()).hasSize(2);
        assertThat(result.matches().get(0).embedded().text()).isEqualTo("chunk2");
        assertThat(result.matches().get(0).score()).isEqualTo(0.95);
        assertThat(result.matches().get(1).embedded().text()).isEqualTo("chunk4");
        // Rerank 后分数是重排相关性分数
        assertThat(result.scoreType()).isEqualTo(RetrieveResult.ScoreType.RERANK);
    }

    @Test
    void rerank_failure_falls_back_to_vector_order() {
        mockVectorMatches(5);
        when(rerankService.scoreAll(anyList(), anyString(), anyLong()))
                .thenThrow(new IllegalStateException("HTTP 401"));

        RetrieveResult result = service.searchMultiple(List.of(1L), "q", new RetrievalOptions(99L));

        assertThat(result.rerankApplied()).isFalse();
        assertThat(result.matches()).hasSize(2);
        assertThat(result.matches().get(0).embedded().text()).isEqualTo("chunk0");
        // Rerank 失败降级，分数仍是原始余弦相似度
        assertThat(result.scoreType()).isEqualTo(RetrieveResult.ScoreType.COSINE);
    }

    @Test
    void hybrid_fuses_keyword_only_hits_and_marks_source() {
        mockVectorMatches(3);
        // 关键词路召回：chunk1（两路命中）+ kwOnly（仅关键词）
        when(keywordSearchService.search(eq("kb_1"), eq("q"), anyInt())).thenReturn(List.of(
                new EmbeddingMatch<>(0.6, "id1", null, TextSegment.from("chunk1")),
                new EmbeddingMatch<>(0.55, "kw1", null, TextSegment.from("kwOnly"))));

        RetrieveResult result = service.searchMultiple(List.of(1L), "q", new RetrievalOptions(null, true));

        assertThat(result.hybridApplied()).isTrue();
        assertThat(result.keywordHitIds()).containsExactly("kw1");
        assertThat(result.matches()).hasSize(2);
        // RRF 后 chunk1（两路命中）应排第一
        assertThat(result.matches().get(0).embedded().text()).isEqualTo("chunk1");
        // 多路融合 → RRF 分数，不可用余弦阈值判定命中
        assertThat(result.scoreType()).isEqualTo(RetrieveResult.ScoreType.RRF);
    }

    @Test
    void hybrid_with_empty_keyword_path_degrades_to_vector_order() {
        mockVectorMatches(3);
        when(keywordSearchService.search(anyString(), anyString(), anyInt())).thenReturn(List.of());

        RetrieveResult result = service.searchMultiple(List.of(1L), "q", new RetrievalOptions(null, true));

        assertThat(result.hybridApplied()).isTrue();
        assertThat(result.keywordHitIds()).isEmpty();
        assertThat(result.matches()).hasSize(2);
        assertThat(result.matches().get(0).embedded().text()).isEqualTo("chunk0");
    }

    @Test
    void multi_query_fuses_variant_lists_via_rrf() {
        mockVectorMatches(3);
        when(queryTransformService.transform(eq("q"), eq("MULTI_QUERY"), eq(5L)))
                .thenReturn(new QueryTransformService.TransformResult(
                        List.of("q", "v1", "v2"), List.of("v1", "v2"), true, 12));

        RetrieveResult result = service.searchMultiple(List.of(1L), "q",
                new RetrievalOptions(null, false, "MULTI_QUERY", 5L));

        // 原查询 + 2 变体各 embed 一次并各自召回
        Mockito.verify(embeddingModel).embed("q");
        Mockito.verify(embeddingModel).embed("v1");
        Mockito.verify(embeddingModel).embed("v2");
        Mockito.verify(store, Mockito.times(3)).search(any());
        assertThat(result.transformedQueries()).containsExactly("v1", "v2");
        assertThat(result.transformLatencyMs()).isEqualTo(12);
        assertThat(result.matches()).hasSize(2);
        // 多查询变体多路召回 → RRF 分数
        assertThat(result.scoreType()).isEqualTo(RetrieveResult.ScoreType.RRF);
    }

    @Test
    void hyde_replaces_vector_query_with_hypothetical_answer() {
        mockVectorMatches(3);
        when(queryTransformService.transform(eq("q"), eq("HYDE"), eq(5L)))
                .thenReturn(new QueryTransformService.TransformResult(
                        List.of("假设答案"), List.of("假设答案"), true, 20));

        RetrieveResult result = service.searchMultiple(List.of(1L), "q",
                new RetrievalOptions(null, false, "HYDE", 5L));

        // 向量路用假设答案做 embedding，原查询不参与向量路
        Mockito.verify(embeddingModel).embed("假设答案");
        Mockito.verify(embeddingModel, Mockito.never()).embed("q");
        assertThat(result.transformedQueries()).containsExactly("假设答案");
        assertThat(result.matches()).hasSize(2);
    }

    @Test
    void transform_degraded_falls_back_to_original_query() {
        mockVectorMatches(3);
        // 变换服务降级：返回原查询、无变换产物
        when(queryTransformService.transform(anyString(), anyString(), anyLong()))
                .thenReturn(new QueryTransformService.TransformResult(List.of("q"), List.of(), false, 0));

        RetrieveResult result = service.searchMultiple(List.of(1L), "q",
                new RetrievalOptions(null, false, "REWRITE", 5L));

        Mockito.verify(embeddingModel).embed("q");
        assertThat(result.transformedQueries()).isEmpty();
        assertThat(result.matches()).hasSize(2);
        assertThat(result.matches().get(0).embedded().text()).isEqualTo("chunk0");
    }
}
