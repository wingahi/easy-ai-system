package com.wgh.rag.kb.service;

import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.metrics.RagMetrics;
import com.wgh.rag.common.result.ResultCode;
import com.wgh.rag.config.property.RagProperties;
import com.wgh.rag.kb.mapper.KnowledgeBaseMapper;
import com.wgh.rag.llm.factory.ModelCacheManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 检索入口的知识库存在性校验：不存在返回 3001，空 kbIds 仍返回空结果
 */
class RagRetrieveServiceKbValidationTest {

    private final KnowledgeBaseMapper kbMapper = Mockito.mock(KnowledgeBaseMapper.class);
    private final ModelCacheManager modelCacheManager = Mockito.mock(ModelCacheManager.class);
    private final VectorStoreService vectorStoreService = Mockito.mock(VectorStoreService.class);
    private final RagMetrics metrics = Mockito.mock(RagMetrics.class);

    private RagRetrieveService service;

    @BeforeEach
    void setUp() {
        RagProperties props = new RagProperties();
        service = new RagRetrieveService(kbMapper, modelCacheManager, vectorStoreService, props,
                Mockito.mock(RerankService.class), Mockito.mock(KeywordSearchService.class),
                Mockito.mock(QueryTransformService.class), metrics);
    }

    @Test
    @DisplayName("kb 不存在：抛 3001 且不加载 embedding 模型")
    void searchMultiple_missingKb() {
        when(kbMapper.selectById(999999L)).thenReturn(null);

        assertThatThrownBy(() -> service.searchMultiple(List.of(999999L), "查询"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(ResultCode.RESOURCE_NOT_FOUND.getCode()))
                .hasMessage("知识库不存在");

        verify(modelCacheManager, never()).getEmbeddingModel(any());
    }

    @Test
    @DisplayName("kbIds 为空：返回空检索结果")
    void searchMultiple_emptyKbIds() {
        List<dev.langchain4j.store.embedding.EmbeddingMatch<dev.langchain4j.data.segment.TextSegment>> result =
                service.searchMultiple(List.<Long>of(), "查询");

        assertThat(result).isEmpty();
    }
}
