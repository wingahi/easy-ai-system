package com.wgh.rag.kb.service;

import com.wgh.rag.common.metrics.RagMetrics;
import com.wgh.rag.config.property.RagProperties;
import com.wgh.rag.kb.entity.KnowledgeBase;
import com.wgh.rag.kb.mapper.KnowledgeBaseMapper;
import com.wgh.rag.llm.factory.ModelCacheManager;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 读取侧 kbName 兜底：存量向量无 kbName 时按 kbId 回查，且不得因缺字段报错。
 */
class RagRetrieveServiceKbNameTest {

    private final KnowledgeBaseMapper kbMapper = Mockito.mock(KnowledgeBaseMapper.class);
    private final ModelCacheManager modelCacheManager = Mockito.mock(ModelCacheManager.class);
    private final VectorStoreService vectorStoreService = Mockito.mock(VectorStoreService.class);
    private final EmbeddingModel embeddingModel = Mockito.mock(EmbeddingModel.class);
    private final PgVectorEmbeddingStore store = Mockito.mock(PgVectorEmbeddingStore.class);

    private RagRetrieveService service;

    @BeforeEach
    void setUp() {
        RagProperties props = new RagProperties();
        props.getRetrieve().setTopK(5);
        props.getRetrieve().setMinScore(0.0);
        service = new RagRetrieveService(kbMapper, modelCacheManager, vectorStoreService, props,
                Mockito.mock(RerankService.class), Mockito.mock(KeywordSearchService.class),
                Mockito.mock(QueryTransformService.class), Mockito.mock(RagMetrics.class));

        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(1L);
        kb.setName("产品手册库");
        kb.setCollectionName("kb_1");
        kb.setEmbeddingModelId(7L);
        kb.setDimension(1536);
        when(kbMapper.selectById(1L)).thenReturn(kb);
        when(modelCacheManager.getEmbeddingModel(7L)).thenReturn(embeddingModel);
        when(embeddingModel.embed(anyString())).thenReturn(Response.from(new Embedding(new float[]{0.1f})));
        Mockito.doReturn(store).when(vectorStoreService).createEmbeddingStore(eq("kb_1"), anyInt());
    }

    private void mockVectorMatches(List<EmbeddingMatch<TextSegment>> matches) {
        when(store.search(any())).thenReturn(new EmbeddingSearchResult<>(matches));
    }

    @Test
    @DisplayName("存量向量缺 kbName：按 kbId 回查知识库名称补齐")
    void legacyVectorWithoutKbName_isBackfilled() {
        mockVectorMatches(List.of(matchWithMetadata(Map.of("kbId", 1L, "fileName", "a.pdf"))));

        RetrieveResult result = service.searchMultiple(List.of(1L), "q", RetrievalOptions.defaults());

        assertThat(result.matches()).hasSize(1);
        assertThat(result.matches().get(0).embedded().metadata().getString("kbName")).isEqualTo("产品手册库");
    }

    @Test
    @DisplayName("存量向量缺 kbName 且缺 kbId：单库检索回落到该库名称")
    void legacyVectorWithoutKbId_usesSingleKbName() {
        mockVectorMatches(List.of(matchWithMetadata(Map.of("fileName", "a.pdf"))));

        RetrieveResult result = service.searchMultiple(List.of(1L), "q", RetrievalOptions.defaults());

        assertThat(result.matches().get(0).embedded().metadata().getString("kbName")).isEqualTo("产品手册库");
    }

    @Test
    @DisplayName("已带 kbName 的新数据：原样保留，不覆盖")
    void existingKbName_isPreserved() {
        mockVectorMatches(List.of(matchWithMetadata(Map.of("kbId", 1L, "kbName", "旧名称"))));

        RetrieveResult result = service.searchMultiple(List.of(1L), "q", RetrievalOptions.defaults());

        assertThat(result.matches().get(0).embedded().metadata().getString("kbName")).isEqualTo("旧名称");
    }

    @Test
    @DisplayName("无 metadata 的历史数据：读取端不崩溃")
    void segmentWithoutMetadata_doesNotFail() {
        mockVectorMatches(List.of(new EmbeddingMatch<>(0.9, "id-1", null, TextSegment.from("纯文本"))));

        RetrieveResult result = service.searchMultiple(List.of(1L), "q", RetrievalOptions.defaults());

        assertThat(result.matches()).hasSize(1);
        assertThat(result.matches().get(0).embedded().text()).isEqualTo("纯文本");
    }

    private static EmbeddingMatch<TextSegment> matchWithMetadata(Map<String, Object> meta) {
        return new EmbeddingMatch<>(0.9, "id-1", null, TextSegment.from("chunk", Metadata.from(meta)));
    }
}
