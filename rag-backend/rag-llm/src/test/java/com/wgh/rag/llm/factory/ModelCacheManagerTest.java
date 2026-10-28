package com.wgh.rag.llm.factory;

import com.wgh.rag.llm.entity.EmbeddingModelConfig;
import com.wgh.rag.llm.entity.LlmModel;
import com.wgh.rag.llm.mapper.EmbeddingModelMapper;
import com.wgh.rag.llm.mapper.LlmModelMapper;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ModelCacheManager 缓存语义测试（重构前补录）：
 * computeIfAbsent 缓存命中、配置变更后 evict 失效重建、异常路径。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("模型缓存管理器语义测试")
class ModelCacheManagerTest {

    @Mock
    private LlmModelMapper llmModelMapper;
    @Mock
    private EmbeddingModelMapper embeddingModelMapper;

    private ModelCacheManager cacheManager;

    @BeforeEach
    void setUp() {
        cacheManager = new ModelCacheManager(ChatModelFactoryRegistryTest.defaultRegistry(),
                llmModelMapper, embeddingModelMapper);
    }

    private LlmModel chatConfig(Long id) {
        LlmModel config = new LlmModel();
        config.setId(id);
        config.setName("测试模型" + id);
        config.setProvider("OPENAI");
        config.setModelName("gpt-4o-mini");
        config.setBaseUrl("http://localhost:8088/v1");
        config.setApiKey("sk-demo");
        return config;
    }

    private EmbeddingModelConfig embeddingConfig(Long id) {
        EmbeddingModelConfig config = new EmbeddingModelConfig();
        config.setId(id);
        config.setName("测试嵌入" + id);
        config.setProvider("OPENAI");
        config.setModelName("text-embedding-3-small");
        config.setBaseUrl("http://localhost:8088/v1");
        config.setApiKey("sk-demo");
        return config;
    }

    @Test
    @DisplayName("getChatModel - modelId 为 null 抛 IllegalArgumentException")
    void getChatModel_nullId() {
        assertThatThrownBy(() -> cacheManager.getChatModel(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("modelId 不能为 null");
    }

    @Test
    @DisplayName("getChatModel - 同 id 命中缓存，DB 只查一次且返回同一实例")
    void getChatModel_cacheHit() {
        when(llmModelMapper.selectById(1L)).thenReturn(chatConfig(1L));

        ChatModel first = cacheManager.getChatModel(1L);
        ChatModel second = cacheManager.getChatModel(1L);

        assertThat(first).isSameAs(second);
        verify(llmModelMapper, times(1)).selectById(1L);
    }

    @Test
    @DisplayName("getChatModel - 模型不存在抛 IllegalArgumentException")
    void getChatModel_notFound() {
        when(llmModelMapper.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> cacheManager.getChatModel(99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("LLM模型不存在: 99");
    }

    @Test
    @DisplayName("getStreamingChatModel - 同 id 命中缓存")
    void getStreamingChatModel_cacheHit() {
        when(llmModelMapper.selectById(2L)).thenReturn(chatConfig(2L));

        StreamingChatModel first = cacheManager.getStreamingChatModel(2L);
        StreamingChatModel second = cacheManager.getStreamingChatModel(2L);

        assertThat(first).isSameAs(second);
        verify(llmModelMapper, times(1)).selectById(2L);
    }

    @Test
    @DisplayName("evictChatModel - 清除后重新查询 DB 并重建实例")
    void evictChatModel_rebuild() {
        when(llmModelMapper.selectById(3L)).thenReturn(chatConfig(3L));

        ChatModel first = cacheManager.getChatModel(3L);
        cacheManager.evictChatModel(3L);
        ChatModel second = cacheManager.getChatModel(3L);

        assertThat(first).isNotSameAs(second);
        verify(llmModelMapper, times(2)).selectById(3L);
    }

    @Test
    @DisplayName("getEmbeddingModel - 同 id 命中缓存，evict 后重建")
    void embeddingModel_cacheAndEvict() {
        when(embeddingModelMapper.selectById(4L)).thenReturn(embeddingConfig(4L));

        EmbeddingModel first = cacheManager.getEmbeddingModel(4L);
        EmbeddingModel second = cacheManager.getEmbeddingModel(4L);
        assertThat(first).isSameAs(second);

        cacheManager.evictEmbeddingModel(4L);
        EmbeddingModel third = cacheManager.getEmbeddingModel(4L);
        assertThat(first).isNotSameAs(third);
        verify(embeddingModelMapper, times(2)).selectById(4L);
    }

    @Test
    @DisplayName("getEmbeddingModel - 嵌入模型不存在抛 IllegalArgumentException")
    void getEmbeddingModel_notFound() {
        when(embeddingModelMapper.selectById(98L)).thenReturn(null);

        assertThatThrownBy(() -> cacheManager.getEmbeddingModel(98L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("嵌入模型不存在: 98");
    }
}
