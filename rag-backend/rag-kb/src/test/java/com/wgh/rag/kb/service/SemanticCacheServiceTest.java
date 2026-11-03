package com.wgh.rag.kb.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wgh.rag.config.property.RagProperties;
import com.wgh.rag.kb.entity.KnowledgeBase;
import com.wgh.rag.kb.entity.SemanticCache;
import com.wgh.rag.kb.mapper.KnowledgeBaseMapper;
import com.wgh.rag.kb.mapper.SemanticCacheMapper;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SemanticCacheService 单测：命中阈值、窗口隔离、淘汰逻辑、降级行为
 */
class SemanticCacheServiceTest {

    private final SemanticCacheMapper cacheMapper = Mockito.mock(SemanticCacheMapper.class);
    private final KnowledgeBaseMapper kbMapper = Mockito.mock(KnowledgeBaseMapper.class);
    private final VectorStoreService vectorStoreService = Mockito.mock(VectorStoreService.class);
    private final ModelCacheManager modelCacheManager = Mockito.mock(ModelCacheManager.class);
    private final EmbeddingModel embeddingModel = Mockito.mock(EmbeddingModel.class);
    private final PgVectorEmbeddingStore store = Mockito.mock(PgVectorEmbeddingStore.class);

    private RagProperties props;
    private SemanticCacheService service;

    @BeforeEach
    void setUp() {
        props = new RagProperties();
        service = new SemanticCacheService(cacheMapper, kbMapper, vectorStoreService, modelCacheManager, props);

        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(1L);
        kb.setEmbeddingModelId(7L);
        kb.setDimension(1536);
        when(kbMapper.selectById(1L)).thenReturn(kb);
        when(modelCacheManager.getEmbeddingModel(7L)).thenReturn(embeddingModel);
        when(embeddingModel.embed(anyString())).thenReturn(Response.from(new Embedding(new float[]{0.1f})));
        Mockito.doReturn(store).when(vectorStoreService)
                .createEmbeddingStore(eq(SemanticCacheService.VEC_TABLE), anyInt());
    }

    @Test
    void lookup_returns_null_when_globally_disabled() {
        props.getCache().setEnabled(false);
        assertThat(service.lookup(1L, List.of(1L), "问题")).isNull();
        verifyNoInteractions(store);
    }

    @Test
    void lookup_returns_null_when_no_kb() {
        assertThat(service.lookup(1L, List.of(), "问题")).isNull();
        assertThat(service.lookup(1L, null, "问题")).isNull();
        verifyNoInteractions(store);
    }

    @Test
    void lookup_returns_null_when_below_threshold() {
        when(store.search(any())).thenReturn(new EmbeddingSearchResult<>(List.of(
                new EmbeddingMatch<>(0.80, "vec-1", null, TextSegment.from("q")))));
        assertThat(service.lookup(1L, List.of(1L), "问题")).isNull();
        verify(cacheMapper, never()).updateById(any(SemanticCache.class));
    }

    @Test
    void lookup_returns_null_when_no_match() {
        when(store.search(any())).thenReturn(new EmbeddingSearchResult<>(List.of()));
        assertThat(service.lookup(1L, List.of(1L), "问题")).isNull();
    }

    @Test
    void lookup_hit_returns_answer_and_increments_hit_count() {
        when(store.search(any())).thenReturn(new EmbeddingSearchResult<>(List.of(
                new EmbeddingMatch<>(0.97, "vec-1", null, TextSegment.from("q")))));
        SemanticCache row = new SemanticCache();
        row.setId(11L);
        row.setWindowId(1L);
        row.setEmbeddingId("vec-1");
        row.setAnswerText("缓存答案");
        row.setHitCount(3);
        when(cacheMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(row);

        SemanticCacheService.CacheHit hit = service.lookup(1L, List.of(1L), "相似问题");

        assertThat(hit).isNotNull();
        assertThat(hit.answer()).isEqualTo("缓存答案");
        assertThat(hit.score()).isEqualTo(0.97);
        assertThat(hit.cacheId()).isEqualTo(11L);
        var captor = org.mockito.ArgumentCaptor.forClass(SemanticCache.class);
        verify(cacheMapper).updateById(captor.capture());
        assertThat(captor.getValue().getHitCount()).isEqualTo(4);
    }

    @Test
    void lookup_degrades_to_null_on_store_exception() {
        when(store.search(any())).thenThrow(new RuntimeException("dimension mismatch"));
        assertThat(service.lookup(1L, List.of(1L), "问题")).isNull();
    }

    @Test
    void save_skips_when_blank_answer_or_no_kb() {
        service.save(1L, List.of(1L), "问题", "  ");
        service.save(1L, List.of(), "问题", "答案");
        verify(cacheMapper, never()).insert(any(SemanticCache.class));
    }

    @Test
    void save_persists_row_and_vector() {
        when(store.add(any(Embedding.class), any(TextSegment.class))).thenReturn("vec-9");
        when(cacheMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        service.save(1L, List.of(1L), "问题", "答案");

        verify(store).add(any(Embedding.class), any(TextSegment.class));
        var captor = org.mockito.ArgumentCaptor.forClass(SemanticCache.class);
        verify(cacheMapper).insert(captor.capture());
        SemanticCache saved = captor.getValue();
        assertThat(saved.getWindowId()).isEqualTo(1L);
        assertThat(saved.getEmbeddingId()).isEqualTo("vec-9");
        assertThat(saved.getAnswerText()).isEqualTo("答案");
    }

    @Test
    void save_evicts_oldest_when_over_max() {
        props.getCache().setMaxPerWindow(2);
        when(store.add(any(Embedding.class), any(TextSegment.class))).thenReturn("vec-new");
        when(cacheMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(4L);
        SemanticCache old1 = new SemanticCache();
        old1.setId(1L);
        old1.setEmbeddingId("vec-1");
        SemanticCache old2 = new SemanticCache();
        old2.setId(2L);
        old2.setEmbeddingId("vec-2");
        when(cacheMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(old1, old2));

        service.save(1L, List.of(1L), "问题", "答案");

        verify(cacheMapper).physicalDeleteById(1L);
        verify(cacheMapper).physicalDeleteById(2L);
        verify(vectorStoreService).deleteByEmbeddingIds(eq(SemanticCacheService.VEC_TABLE),
                eq(List.of("vec-1", "vec-2")));
    }

    @Test
    void lookup_skips_expired_row() {
        props.getCache().setTtlMinutes(60);
        when(store.search(any())).thenReturn(new EmbeddingSearchResult<>(List.of(
                new EmbeddingMatch<>(0.98, "vec-old", null, TextSegment.from("q")))));
        SemanticCache row = new SemanticCache();
        row.setId(12L);
        row.setWindowId(1L);
        row.setEmbeddingId("vec-old");
        row.setAnswerText("旧答案");
        row.setCreateTime(LocalDateTime.now().minusHours(2));
        when(cacheMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(row);

        assertThat(service.lookup(1L, List.of(1L), "问题")).isNull();
        verify(cacheMapper, never()).updateById(any(SemanticCache.class));
    }

    @Test
    void cleanupExpired_deletes_rows_and_vectors() {
        props.getCache().setTtlMinutes(60);
        SemanticCache expired = new SemanticCache();
        expired.setId(21L);
        expired.setWindowId(1L);
        expired.setEmbeddingId("vec-expired");
        when(cacheMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(expired));

        int removed = service.cleanupExpired();

        assertThat(removed).isEqualTo(1);
        verify(cacheMapper).physicalDeleteById(21L);
        verify(vectorStoreService).deleteByEmbeddingIds(SemanticCacheService.VEC_TABLE, List.of("vec-expired"));
    }

    @Test
    void cleanupExpired_skips_when_ttl_disabled() {
        props.getCache().setTtlMinutes(0);
        assertThat(service.cleanupExpired()).isZero();
        verifyNoInteractions(cacheMapper);
    }

    @Test
    void invalidateByWindow_removes_window_cache_and_vectors() {
        SemanticCache row = new SemanticCache();
        row.setId(31L);
        row.setWindowId(7L);
        row.setEmbeddingId("vec-w7");
        when(cacheMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(row));

        int removed = service.invalidateByWindow(7L);

        assertThat(removed).isEqualTo(1);
        verify(cacheMapper).physicalDeleteById(31L);
        verify(vectorStoreService).deleteByEmbeddingIds(SemanticCacheService.VEC_TABLE, List.of("vec-w7"));
    }

    @Test
    void invalidateByKb_clears_only_windows_referencing_kb_and_next_lookup_misses() {
        when(cacheMapper.selectWindowKbMappings()).thenReturn(List.of(
                Map.of("windowId", 1L, "kbIds", "[1,2]"),
                Map.of("windowId", 2L, "kbIds", "[3]")));
        SemanticCache row = new SemanticCache();
        row.setId(41L);
        row.setWindowId(1L);
        row.setEmbeddingId("vec-w1");
        when(cacheMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(row));

        int removed = service.invalidateByKbId(1L);

        assertThat(removed).isEqualTo(1);
        verify(cacheMapper).physicalDeleteById(41L);
        verify(vectorStoreService).deleteByEmbeddingIds(SemanticCacheService.VEC_TABLE, List.of("vec-w1"));
        // 变更后缓存向量已清理，再次查询不应命中旧答案
        when(store.search(any())).thenReturn(new EmbeddingSearchResult<>(List.of()));
        assertThat(service.lookup(1L, List.of(1L), "问题")).isNull();
    }

    @Test
    void save_failure_does_not_propagate() {
        when(store.add(any(Embedding.class), any(TextSegment.class)))
                .thenThrow(new RuntimeException("pg error"));
        assertThatCode(() -> service.save(1L, List.of(1L), "问题", "答案")).doesNotThrowAnyException();
    }
}
