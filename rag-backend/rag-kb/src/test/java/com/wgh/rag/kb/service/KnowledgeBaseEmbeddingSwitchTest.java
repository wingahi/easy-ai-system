package com.wgh.rag.kb.service;

import com.wgh.rag.common.metrics.RagMetrics;
import com.wgh.rag.config.property.RagProperties;
import com.wgh.rag.kb.entity.KnowledgeBase;
import com.wgh.rag.kb.entity.KnowledgeFile;
import com.wgh.rag.kb.mapper.KnowledgeBaseMapper;
import com.wgh.rag.kb.mapper.KnowledgeFileMapper;
import com.wgh.rag.kb.port.VectorStorePort;
import com.wgh.rag.llm.entity.EmbeddingModelConfig;
import com.wgh.rag.llm.factory.ModelCacheManager;
import com.wgh.rag.llm.service.LlmModelService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 换 embedding 模型必须切表重建：旧 collection 删除、新 collection 生效，
 * 检索只读新表（旧语义空间向量不可能再被召回）；模型未变时不做任何切换。
 */
@DisplayName("知识库 - embedding 模型切换与重建")
class KnowledgeBaseEmbeddingSwitchTest {

    private final KnowledgeBaseMapper kbMapper = Mockito.mock(KnowledgeBaseMapper.class);
    private final KnowledgeFileMapper fileMapper = Mockito.mock(KnowledgeFileMapper.class);
    private final LlmModelService llmModelService = Mockito.mock(LlmModelService.class);
    private final VectorStorePort vectorStoreService = Mockito.mock(VectorStorePort.class);
    private final SemanticCacheService semanticCacheService = Mockito.mock(SemanticCacheService.class);
    private final ApplicationEventPublisher eventPublisher = Mockito.mock(ApplicationEventPublisher.class);

    private KnowledgeBaseService service;

    @BeforeAll
    static void initMybatisTableInfo() {
        // 单测无 MyBatis 上下文：LambdaUpdateWrapper.set 会立即解析列名，需先注册实体元数据
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), KnowledgeFile.class);
    }

    @BeforeEach
    void setUp() {
        service = new KnowledgeBaseService(kbMapper, fileMapper, llmModelService,
                vectorStoreService, semanticCacheService, eventPublisher);
    }

    @Test
    @DisplayName("模型变更：切换新 collection、删除旧 collection、标记文件待重建并发布事件")
    void update_modelChanged_switchesCollectionAndDropsOld() {
        KnowledgeBase existing = kb(1L, "wgh_kb_old", 7L, 1536);
        when(kbMapper.selectById(1L)).thenReturn(existing);
        when(llmModelService.getEmbeddingModel(8L)).thenReturn(embeddingModel(8L, 768));

        KnowledgeBase request = new KnowledgeBase();
        request.setId(1L);
        request.setEmbeddingModelId(8L);
        request.setName("知识库");

        KnowledgeBase updated = service.update(request);

        assertThat(updated.getCollectionName())
                .startsWith("wgh_kb_1_")
                .isNotEqualTo("wgh_kb_old");
        assertThat(updated.getDimension()).isEqualTo(768);
        assertThat(updated.getChunkCount()).isZero();

        verify(vectorStoreService).ensureCollection(updated.getCollectionName(), 768);
        verify(vectorStoreService).dropCollection("wgh_kb_old");
        verify(fileMapper).update(isNull(), any());
        verify(semanticCacheService).invalidateByKbId(1L);
        verify(eventPublisher).publishEvent(any(KbEmbeddingModelChangedEvent.class));
        verify(kbMapper).updateById(request);
    }

    @Test
    @DisplayName("模型未变：保持原 collection，不触发重建")
    void update_modelUnchanged_keepsCollection() {
        KnowledgeBase existing = kb(1L, "wgh_kb_old", 7L, 1536);
        when(kbMapper.selectById(1L)).thenReturn(existing);

        KnowledgeBase request = new KnowledgeBase();
        request.setId(1L);
        request.setEmbeddingModelId(7L);
        request.setName("改名");

        KnowledgeBase updated = service.update(request);

        assertThat(updated.getCollectionName()).isNull();
        verifyNoInteractions(vectorStoreService, eventPublisher, semanticCacheService);
        verify(fileMapper, never()).update(any(), any());
        verify(kbMapper).updateById(request);
    }

    @Test
    @DisplayName("模型变更后检索只走新 collection，旧表向量不参与召回")
    void update_modelChanged_oldCollectionNotRecallable() {
        KnowledgeBase existing = kb(1L, "wgh_kb_old", 7L, 1536);
        KnowledgeBase request = new KnowledgeBase();
        request.setId(1L);
        request.setEmbeddingModelId(8L);
        // 第一次查询为 update 读取旧值，第二次为检索读取切换后的新值
        when(kbMapper.selectById(1L)).thenReturn(existing, request);
        when(llmModelService.getEmbeddingModel(8L)).thenReturn(embeddingModel(8L, 768));

        service.update(request);
        String newCollection = request.getCollectionName();
        assertThat(newCollection).isNotEqualTo("wgh_kb_old");

        EmbeddingModel newModel = mock(EmbeddingModel.class);
        when(newModel.embed(anyString())).thenReturn(Response.from(new Embedding(new float[]{0.1f})));
        ModelCacheManager modelCacheManager = mock(ModelCacheManager.class);
        when(modelCacheManager.getEmbeddingModel(8L)).thenReturn(newModel);

        EmbeddingStore<TextSegment> newStore = mock(PgVectorEmbeddingStore.class);
        when(newStore.search(any())).thenReturn(new EmbeddingSearchResult<>(List.of()));
        when(vectorStoreService.createEmbeddingStore(anyString(), anyInt())).thenReturn(newStore);
        when(vectorStoreService.createEmbeddingStore(eq(newCollection), eq(768))).thenReturn(newStore);

        RagProperties props = new RagProperties();
        props.getRetrieve().setTopK(3);
        props.getRetrieve().setMinScore(0.4);
        RagRetrieveService retrieveService = new RagRetrieveService(
                kbMapper, modelCacheManager, vectorStoreService, props,
                mock(RerankService.class), mock(KeywordSearchService.class),
                mock(QueryTransformService.class), mock(RagMetrics.class));

        RetrieveResult result = retrieveService.searchMultiple(
                List.of(1L), "问题", RetrievalOptions.defaults());

        assertThat(result.matches()).isEmpty();
        verify(vectorStoreService).createEmbeddingStore(eq(newCollection), eq(768));
        verify(vectorStoreService, never()).createEmbeddingStore(eq("wgh_kb_old"), anyInt());
    }

    private static KnowledgeBase kb(Long id, String collection, Long modelId, Integer dimension) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(id);
        kb.setCollectionName(collection);
        kb.setEmbeddingModelId(modelId);
        kb.setDimension(dimension);
        return kb;
    }

    private static EmbeddingModelConfig embeddingModel(Long id, Integer dimension) {
        EmbeddingModelConfig model = new EmbeddingModelConfig();
        model.setId(id);
        model.setDimension(dimension);
        return model;
    }
}
