package com.wgh.rag.llm.factory;

import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.llm.entity.EmbeddingModelConfig;
import com.wgh.rag.llm.entity.LlmModel;
import dev.langchain4j.community.model.dashscope.QwenChatModel;
import dev.langchain4j.community.model.dashscope.QwenEmbeddingModel;
import dev.langchain4j.community.model.dashscope.QwenStreamingChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * LlmModelFactory 现状行为测试（重构前补录）——只构建对象、断言类型与路由，不实际调用 API。
 */
@DisplayName("LLM 模型工厂现状行为测试")
class LlmModelFactoryTest {

    private final LlmModelFactory factory = new LlmModelFactory();

    private LlmModel chatConfig(String provider) {
        LlmModel config = new LlmModel();
        config.setProvider(provider);
        config.setModelName("test-model");
        config.setBaseUrl("http://localhost:8088/v1");
        config.setApiKey("sk-demo");
        config.setTemperature(0.7);
        config.setMaxContextTokens(4096);
        return config;
    }

    private EmbeddingModelConfig embeddingConfig(String provider) {
        EmbeddingModelConfig config = new EmbeddingModelConfig();
        config.setProvider(provider);
        config.setModelName("test-embedding");
        config.setBaseUrl("http://localhost:8088/v1");
        config.setApiKey("sk-demo");
        return config;
    }

    // ==================== ChatModel ====================

    /** OpenAI 风格的三种 provider 均构建出 OpenAiChatModel。 */
    @Test
    @DisplayName("ChatModel - OPENAI/DEEPSEEK/CUSTOM_OPENAI 均构建 OpenAiChatModel")
    void chatModel_openAiStyleProviders() {
        assertThat(factory.createChatModel(chatConfig("OPENAI"))).isInstanceOf(OpenAiChatModel.class);
        assertThat(factory.createChatModel(chatConfig("DEEPSEEK"))).isInstanceOf(OpenAiChatModel.class);
        assertThat(factory.createChatModel(chatConfig("CUSTOM_OPENAI"))).isInstanceOf(OpenAiChatModel.class);
    }

    /** DashScope 与 QWEN 别名均构建出 QwenChatModel。 */
    @Test
    @DisplayName("ChatModel - DASHSCOPE/QWEN 均构建 QwenChatModel")
    void chatModel_dashScopeProviders() {
        assertThat(factory.createChatModel(chatConfig("DASHSCOPE"))).isInstanceOf(QwenChatModel.class);
        assertThat(factory.createChatModel(chatConfig("QWEN"))).isInstanceOf(QwenChatModel.class);
    }

    /** provider 名称大小写不影响 ChatModel 实现的选择。 */
    @Test
    @DisplayName("ChatModel - provider 大小写不敏感")
    void chatModel_caseInsensitive() {
        assertThat(factory.createChatModel(chatConfig("openai"))).isInstanceOf(OpenAiChatModel.class);
        assertThat(factory.createChatModel(chatConfig("DashScope"))).isInstanceOf(QwenChatModel.class);
    }

    /** 不支持的 provider 构建 ChatModel 时抛 BusinessException 并带提供商信息。 */
    @Test
    @DisplayName("ChatModel - 不支持的 provider 抛 BusinessException")
    void chatModel_unsupportedProvider() {
        LlmModel config = chatConfig("GOOGLE");

        assertThatThrownBy(() -> factory.createChatModel(config))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不支持的LLM提供商: GOOGLE");
    }

    // ==================== StreamingChatModel ====================

    /** OpenAI 风格 provider 构建流式模型时均返回 OpenAiStreamingChatModel。 */
    @Test
    @DisplayName("StreamingChatModel - OPENAI 风格 provider 构建 OpenAiStreamingChatModel")
    void streamingChatModel_openAiStyleProviders() {
        assertThat(factory.createStreamingChatModel(chatConfig("OPENAI"))).isInstanceOf(OpenAiStreamingChatModel.class);
        assertThat(factory.createStreamingChatModel(chatConfig("DEEPSEEK"))).isInstanceOf(OpenAiStreamingChatModel.class);
        assertThat(factory.createStreamingChatModel(chatConfig("CUSTOM_OPENAI"))).isInstanceOf(OpenAiStreamingChatModel.class);
    }

    /** DashScope 与 QWEN 别名构建流式模型时均返回 QwenStreamingChatModel。 */
    @Test
    @DisplayName("StreamingChatModel - DASHSCOPE/QWEN 构建 QwenStreamingChatModel")
    void streamingChatModel_dashScopeProviders() {
        assertThat(factory.createStreamingChatModel(chatConfig("DASHSCOPE"))).isInstanceOf(QwenStreamingChatModel.class);
        assertThat(factory.createStreamingChatModel(chatConfig("QWEN"))).isInstanceOf(QwenStreamingChatModel.class);
    }

    /** 不支持的 provider 构建流式模型时抛 BusinessException。 */
    @Test
    @DisplayName("StreamingChatModel - 不支持的 provider 抛 BusinessException")
    void streamingChatModel_unsupportedProvider() {
        LlmModel config = chatConfig("GOOGLE");

        assertThatThrownBy(() -> factory.createStreamingChatModel(config))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不支持的LLM提供商: GOOGLE");
    }

    // ==================== EmbeddingModel ====================

    /** OPENAI 与 CUSTOM_OPENAI 构建嵌入模型时返回 OpenAiEmbeddingModel。 */
    @Test
    @DisplayName("EmbeddingModel - OPENAI/CUSTOM_OPENAI 构建 OpenAiEmbeddingModel")
    void embeddingModel_openAiStyleProviders() {
        assertThat(factory.createEmbeddingModel(embeddingConfig("OPENAI"))).isInstanceOf(OpenAiEmbeddingModel.class);
        assertThat(factory.createEmbeddingModel(embeddingConfig("CUSTOM_OPENAI"))).isInstanceOf(OpenAiEmbeddingModel.class);
    }

    /** DashScope 与 QWEN 别名构建嵌入模型时返回 QwenEmbeddingModel。 */
    @Test
    @DisplayName("EmbeddingModel - DASHSCOPE/QWEN 构建 QwenEmbeddingModel")
    void embeddingModel_dashScopeProviders() {
        assertThat(factory.createEmbeddingModel(embeddingConfig("DASHSCOPE"))).isInstanceOf(QwenEmbeddingModel.class);
        assertThat(factory.createEmbeddingModel(embeddingConfig("QWEN"))).isInstanceOf(QwenEmbeddingModel.class);
    }

    /** DEEPSEEK 不支持嵌入模型，构建时抛 BusinessException。 */
    @Test
    @DisplayName("EmbeddingModel - DEEPSEEK 不支持嵌入，抛 BusinessException")
    void embeddingModel_deepSeekUnsupported() {
        EmbeddingModelConfig config = embeddingConfig("DEEPSEEK");

        assertThatThrownBy(() -> factory.createEmbeddingModel(config))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不支持的嵌入模型提供商: DEEPSEEK");
    }

    /** 不支持的 provider 构建嵌入模型时抛 BusinessException。 */
    @Test
    @DisplayName("EmbeddingModel - 不支持的 provider 抛 BusinessException")
    void embeddingModel_unsupportedProvider() {
        EmbeddingModelConfig config = embeddingConfig("XYZ");

        assertThatThrownBy(() -> factory.createEmbeddingModel(config))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不支持的嵌入模型提供商: XYZ");
    }
}
