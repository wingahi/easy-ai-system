package com.wgh.rag.kb.service;

import com.wgh.rag.common.exception.BusinessException;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 换 embedding 模型只置 PENDING 标记，不自动重建；重建中禁止再切模型；
 * 未重建完成前检索继续使用旧 collection + 旧模型（配成对，语义自洽）。
 */
@DisplayName("知识库 - embedding 模型切换只标记不重建")
class KnowledgeBaseEmbeddingSwitchTest {

    private final KnowledgeBaseMapper kbMapper = Mockito.mock(KnowledgeBaseMapper.class);
    private final KnowledgeFileMapper fileMapper = Mockito.mock(KnowledgeFileMapper.class);
    private final LlmModelService llmModelService = Mockito.mock(LlmModelService.class);
    private final VectorStorePort vectorStoreService = Mockito.mock(VectorStorePort.class);
    private final SemanticCacheService semanticCacheService = Mockito.mock(SemanticCacheService.class);

    private KnowledgeBaseService service;

    @BeforeAll
    static void initMybatisTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, KnowledgeFile.class);
        TableInfoHelper.initTableInfo(assistant, KnowledgeBase.class);
    }

    @BeforeEach
    void setUp() {
        service = new KnowledgeBaseService(kbMapper, fileMapper, llmModelService,
                vectorStoreService, semanticCacheService);
    }

    @Test
    @DisplayName("模型变更：只置 PENDING 与待生效模型，不改 collection/模型指针，不触发任何重建")
    void update_modelChanged_onlyMarksPending() {
        KnowledgeBase existing = kb(1L, "wgh_kb_old", 7L, 1536, "");
        when(kbMapper.selectById(1L)).thenReturn(existing);
        when(llmModelService.getEmbeddingModel(8L)).thenReturn(embeddingModel(8L, 768));
        when(fileMapper.selectCount(any())).thenReturn(3L);

        KnowledgeBase request = new KnowledgeBase();
        request.setId(1L);
        request.setEmbeddingModelId(8L);
        request.setName("知识库");

        KnowledgeBase updated = service.update(request);

        assertThat(updated.getRebuildStatus()).isEqualTo("PENDING");
        assertThat(updated.getPendingEmbeddingModelId()).isEqualTo(8L);
        assertThat(updated.getRebuildTotal()).isEqualTo(3);
        assertThat(updated.getRebuildProcessed()).isZero();
        assertThat(updated.getCollectionName()).isNull();
        assertThat(updated.getEmbeddingModelId()).isEqualTo(8L);

        verifyNoInteractions(vectorStoreService, semanticCacheService);
        verify(kbMapper).updateById(request);
    }

    @Test
    @DisplayName("模型未变：普通编辑，不产生重建标记")
    void update_modelUnchanged_keepsStatus() {
        KnowledgeBase existing = kb(1L, "wgh_kb_old", 7L, 1536, "");
        when(kbMapper.selectById(1L)).thenReturn(existing);

        KnowledgeBase request = new KnowledgeBase();
        request.setId(1L);
        request.setEmbeddingModelId(7L);
        request.setName("改名");

        KnowledgeBase updated = service.update(request);

        assertThat(updated.getRebuildStatus()).isNull();
        verifyNoInteractions(llmModelService, vectorStoreService, semanticCacheService);
        verify(kbMapper).updateById(request);
    }

    @Test
    @DisplayName("重建进行中：禁止切换模型")
    void update_whileRebuildRunning_rejected() {
        KnowledgeBase existing = kb(1L, "wgh_kb_old", 7L, 1536, "RUNNING");
        when(kbMapper.selectById(1L)).thenReturn(existing);

        KnowledgeBase request = new KnowledgeBase();
        request.setId(1L);
        request.setEmbeddingModelId(8L);

        assertThatThrownBy(() -> service.update(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("正在重建");
        verify(llmModelService, never()).getEmbeddingModel(any());
        verify(kbMapper, never()).updateById(any(KnowledgeBase.class));
    }

    private static KnowledgeBase kb(Long id, String collection, Long modelId, Integer dimension, String rebuildStatus) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(id);
        kb.setCollectionName(collection);
        kb.setEmbeddingModelId(modelId);
        kb.setDimension(dimension);
        kb.setRebuildStatus(rebuildStatus);
        return kb;
    }

    private static EmbeddingModelConfig embeddingModel(Long id, Integer dimension) {
        EmbeddingModelConfig model = new EmbeddingModelConfig();
        model.setId(id);
        model.setDimension(dimension);
        return model;
    }
}
