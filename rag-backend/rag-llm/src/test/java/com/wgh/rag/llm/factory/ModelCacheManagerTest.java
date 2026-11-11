package com.wgh.rag.llm.factory;

import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.llm.entity.EmbeddingModelConfig;
import com.wgh.rag.llm.entity.LlmModel;
import com.wgh.rag.llm.mapper.EmbeddingModelMapper;
import com.wgh.rag.llm.mapper.LlmModelMapper;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
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

    /** 传入 null 的 modelId 时 getChatModel 抛 IllegalArgumentException。 */
    @Test
    @DisplayName("getChatModel - modelId 为 null 抛 IllegalArgumentException")
    void getChatModel_nullId() {
        assertThatThrownBy(() -> cacheManager.getChatModel(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("modelId 不能为 null");
    }

    /** 相同 id 重复取 ChatModel 命中缓存，返回同一实例且只查库一次。 */
    @Test
    @DisplayName("getChatModel - 同 id 命中缓存，DB 只查一次且返回同一实例")
    void getChatModel_cacheHit() {
        when(llmModelMapper.selectById(1L)).thenReturn(chatConfig(1L));

        ChatModel first = cacheManager.getChatModel(1L);
        ChatModel second = cacheManager.getChatModel(1L);

        assertThat(first).isSameAs(second);
        verify(llmModelMapper, times(1)).selectById(1L);
    }

    /** 模型记录不存在时 getChatModel 抛 IllegalArgumentException。 */
    @Test
    @DisplayName("getChatModel - 模型不存在抛 IllegalArgumentException")
    void getChatModel_notFound() {
        when(llmModelMapper.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> cacheManager.getChatModel(99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("LLM模型不存在: 99");
    }

    /** 相同 id 重复取流式模型命中缓存，返回同一实例且只查库一次。 */
    @Test
    @DisplayName("getStreamingChatModel - 同 id 命中缓存")
    void getStreamingChatModel_cacheHit() {
        when(llmModelMapper.selectById(2L)).thenReturn(chatConfig(2L));

        StreamingChatModel first = cacheManager.getStreamingChatModel(2L);
        StreamingChatModel second = cacheManager.getStreamingChatModel(2L);

        assertThat(first).isSameAs(second);
        verify(llmModelMapper, times(1)).selectById(2L);
    }

    /** 驱逐缓存后再取 ChatModel 会重新查库并构建新实例。 */
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

    /** 嵌入模型同 id 命中缓存，驱逐后重新查库构建新实例。 */
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

    /** 嵌入模型记录不存在时抛 IllegalArgumentException。 */
    @Test
    @DisplayName("getEmbeddingModel - 嵌入模型不存在抛 IllegalArgumentException")
    void getEmbeddingModel_notFound() {
        when(embeddingModelMapper.selectById(98L)).thenReturn(null);

        assertThatThrownBy(() -> cacheManager.getEmbeddingModel(98L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("嵌入模型不存在: 98");
    }

    // ==================== 新增提供商与能力校验 ====================

    /** 新增 OLLAMA provider 经缓存管理器正常构建。 */
    @Test
    @DisplayName("getChatModel - OLLAMA 构建 OpenAiChatModel 并缓存")
    void getChatModel_ollama() {
        LlmModel config = chatConfig(5L);
        config.setProvider("OLLAMA");
        config.setBaseUrl(null);
        config.setApiKey(null);
        when(llmModelMapper.selectById(5L)).thenReturn(config);

        ChatModel first = cacheManager.getChatModel(5L);
        ChatModel second = cacheManager.getChatModel(5L);

        assertThat(first).isInstanceOf(OpenAiChatModel.class);
        assertThat(first).isSameAs(second);
        verify(llmModelMapper, times(1)).selectById(5L);
    }

    /** 历史别名 CUSTOM 归一化为 CUSTOM_OPENAI 后可正常构建。 */
    @Test
    @DisplayName("getChatModel - 历史别名 CUSTOM 归一化为 CUSTOM_OPENAI")
    void getChatModel_legacyCustomAlias() {
        LlmModel config = chatConfig(6L);
        config.setProvider("CUSTOM");
        when(llmModelMapper.selectById(6L)).thenReturn(config);

        assertThat(cacheManager.getChatModel(6L)).isInstanceOf(OpenAiChatModel.class);
    }

    /** 需要 Key 的提供商缺 Key 时抛明确业务异常（可被故障转移捕获）。 */
    @Test
    @DisplayName("getChatModel - 缺 API Key 抛 BusinessException")
    void getChatModel_missingApiKey() {
        LlmModel config = chatConfig(7L);
        config.setProvider("ZHIPU");
        config.setApiKey("");
        when(llmModelMapper.selectById(7L)).thenReturn(config);

        assertThatThrownBy(() -> cacheManager.getChatModel(7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("缺少 API Key");
    }

    /** 仅支持对话的 provider 取嵌入模型时抛嵌入专用异常。 */
    @Test
    @DisplayName("getEmbeddingModel - DEEPSEEK 不支持嵌入抛 BusinessException")
    void getEmbeddingModel_unsupportedProviderCapability() {
        EmbeddingModelConfig config = embeddingConfig(8L);
        config.setProvider("DEEPSEEK");
        when(embeddingModelMapper.selectById(8L)).thenReturn(config);

        assertThatThrownBy(() -> cacheManager.getEmbeddingModel(8L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不支持的嵌入模型提供商: DEEPSEEK");
    }

    /** 未知 provider 在构建前被拦截，错误文案与工厂注册表一致。 */
    @Test
    @DisplayName("getChatModel - 未知 provider 抛 BusinessException")
    void getChatModel_unknownProvider() {
        LlmModel config = chatConfig(9L);
        config.setProvider("GOOGLE");
        when(llmModelMapper.selectById(9L)).thenReturn(config);

        assertThatThrownBy(() -> cacheManager.getChatModel(9L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不支持的LLM提供商: GOOGLE");
    }
}
