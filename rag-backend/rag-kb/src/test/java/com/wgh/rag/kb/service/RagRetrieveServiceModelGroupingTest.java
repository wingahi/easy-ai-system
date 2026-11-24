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
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 跨知识库检索分组语义：不同 embedding 模型必须独立计算 query 向量，模型不可用时按组跳过，
 * 检索参数（topK / minScore）需透传到向量库请求。
 */
@DisplayName("RAG 检索 - 嵌入模型分组")
class RagRetrieveServiceModelGroupingTest {

    private final KnowledgeBaseMapper kbMapper = Mockito.mock(KnowledgeBaseMapper.class);
    private final ModelCacheManager modelCacheManager = Mockito.mock(ModelCacheManager.class);
    private final VectorStoreService vectorStoreService = Mockito.mock(VectorStoreService.class);
    private final RerankService rerankService = Mockito.mock(RerankService.class);
    private final KeywordSearchService keywordSearchService = Mockito.mock(KeywordSearchService.class);
    private final QueryTransformService queryTransformService = Mockito.mock(QueryTransformService.class);

    private final EmbeddingModel modelA = Mockito.mock(EmbeddingModel.class);
    private final EmbeddingModel modelB = Mockito.mock(EmbeddingModel.class);

    private RagRetrieveService service;

    @BeforeEach
    void setUp() {
        RagProperties props = new RagProperties();
        props.getRetrieve().setTopK(5);
        props.getRetrieve().setMinScore(0.4);
        service = new RagRetrieveService(kbMapper, modelCacheManager, vectorStoreService, props,
                rerankService, keywordSearchService, queryTransformService, Mockito.mock(RagMetrics.class));

        when(modelA.embed(anyString())).thenReturn(Response.from(new Embedding(new float[]{0.1f})));
        when(modelB.embed(anyString())).thenReturn(Response.from(new Embedding(new float[]{0.2f})));
    }

    @Test
    @DisplayName("不同 embeddingModelId：每个模型独立 embed 查询并各自检索、结果合并")
    void differentModelsAreSearchedSeparately() {
        mockKb(1L, "kb_a", 7L, 1536);
        mockKb(2L, "kb_b", 8L, 768);
        when(modelCacheManager.getEmbeddingModel(7L)).thenReturn(modelA);
        when(modelCacheManager.getEmbeddingModel(8L)).thenReturn(modelB);
        mockVector("kb_a", matches("a1", "a2"));
        mockVector("kb_b", matches("b1"));

        RetrieveResult result = service.searchMultiple(List.of(1L, 2L), "同一个问题", RetrievalOptions.defaults());

        verify(modelA).embed("同一个问题");
        verify(modelB).embed("同一个问题");
        assertThat(result.matches()).extracting(m -> m.embeddingId())
                .contains("a1", "a2", "b1");
        assertThat(result.hybridApplied()).isFalse();
        // 不同模型分组 => 多路召回 => RRF 融合分数（不能直接与余弦阈值比较）
        assertThat(result.scoreType()).isEqualTo(RetrieveResult.ScoreType.RRF);
    }

    @Test
    @DisplayName("topK 与 minScore 透传给向量库请求")
    void searchParametersArePropagated() {
        mockKb(1L, "kb_a", 7L, 1536);
        when(modelCacheManager.getEmbeddingModel(7L)).thenReturn(modelA);
        mockVector("kb_a", matches("a1"));

        service.searchMultiple(List.of(1L), "q", RetrievalOptions.defaults());

        // 无增强时候选池 = topK=5；minScore 透传 0.4
        verify(vectorStoreService).search(eq("kb_a"), any(), eq(5), eq(0.4), any());
    }

    @Test
    @DisplayName("某组 embedding 模型不可用：跳过该组，不影响其他组召回")
    void unavailableModelGroupIsSkipped() {
        mockKb(1L, "kb_a", 7L, 1536);
        mockKb(2L, "kb_b", 8L, 768);
        when(modelCacheManager.getEmbeddingModel(7L)).thenReturn(modelA);
        when(modelCacheManager.getEmbeddingModel(8L)).thenReturn(null);
        mockVector("kb_a", matches("a1"));

        RetrieveResult result = service.searchMultiple(List.of(1L, 2L), "q", RetrievalOptions.defaults());

        verify(modelB, never()).embed(anyString());
        verify(vectorStoreService, never()).search(eq("kb_b"), any(), anyInt(), anyDouble(), any());
        assertThat(result.matches()).extracting(m -> m.embeddingId()).containsExactly("a1");
    }

    @Test
    @DisplayName("所有知识库均缺少 collectionName/embeddingModelId：返回空结果而不是报错")
    void missingModelMetadataReturnsEmpty() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(1L);
        kb.setCollectionName("kb_a");
        kb.setEmbeddingModelId(null);
        when(kbMapper.selectById(1L)).thenReturn(kb);

        RetrieveResult result = service.searchMultiple(List.of(1L), "q", RetrievalOptions.defaults());

        assertThat(result.matches()).isEmpty();
        verify(vectorStoreService, never()).search(anyString(), any(), anyInt(), anyDouble(), any());
    }

    @Test
    @DisplayName("同一 kbId 每次检索只查一次库（存在性校验与检索字段复用同一条记录）")
    void sameKbIdQueriedOnlyOnce() {
        mockKb(1L, "kb_a", 7L, 1536);
        when(modelCacheManager.getEmbeddingModel(7L)).thenReturn(modelA);
        mockVector("kb_a", matches("a1"));

        service.searchMultiple(List.of(1L), "q", RetrievalOptions.defaults());

        verify(kbMapper, times(1)).selectById(1L);
    }

    @Test
    @DisplayName("空知识库列表：直接返回空，不触发任何检索")
    void emptyKbIdListReturnsEmpty() {
        RetrieveResult result = service.searchMultiple(List.of(), "q", RetrievalOptions.defaults());

        assertThat(result.matches()).isEmpty();
        verify(kbMapper, never()).selectById(any());
        verify(modelA, never()).embed(anyString());
        verify(modelB, never()).embed(anyString());
    }

    // ===== 辅助 =====

    private void mockKb(Long id, String collection, Long modelId, Integer dimension) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(id);
        kb.setCollectionName(collection);
        kb.setEmbeddingModelId(modelId);
        kb.setDimension(dimension);
        when(kbMapper.selectById(id)).thenReturn(kb);
    }

    private void mockVector(String collection, List<VectorMatch> matches) {
        Mockito.doReturn(matches).when(vectorStoreService)
                .search(eq(collection), any(), anyInt(), anyDouble(), any());
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
