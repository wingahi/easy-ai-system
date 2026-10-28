package com.wgh.rag.llm.factory;

import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.llm.entity.EmbeddingModelConfig;
import com.wgh.rag.llm.entity.LlmModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OpenAI 兼容协议工厂（OPENAI / DEEPSEEK / CUSTOM_OPENAI）构建行为测试——不真实调用 API。
 */
@DisplayName("OpenAI 兼容协议模型工厂测试")
class OpenAiCompatibleModelFactoryTest {

    private LlmModel chatConfig() {
        LlmModel config = new LlmModel();
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
        config.setModelName("text-embedding-3-small");
        config.setBaseUrl("http://localhost:8088/v1");
        config.setApiKey("sk-demo");
        return config;
    }

    @Test
    @DisplayName("supportedProvider 标识正确")
    void supportedProvider() {
        assertThat(new OpenAiModelFactory().supportedProvider()).isEqualTo("OPENAI");
        assertThat(new DeepSeekModelFactory().supportedProvider()).isEqualTo("DEEPSEEK");
        assertThat(new CustomOpenAiModelFactory().supportedProvider()).isEqualTo("CUSTOM_OPENAI");
    }

    @Test
    @DisplayName("build - 构建 OpenAiChatModel（含 temperature/maxCompletionTokens 可选参数）")
    void build_openAiChatModel() {
        assertThat(new OpenAiModelFactory().build(chatConfig())).isInstanceOf(OpenAiChatModel.class);
        assertThat(new DeepSeekModelFactory().build(chatConfig())).isInstanceOf(OpenAiChatModel.class);
        assertThat(new CustomOpenAiModelFactory().build(chatConfig())).isInstanceOf(OpenAiChatModel.class);
    }

    @Test
    @DisplayName("build - temperature/maxContextTokens 为 null 时走默认分支仍可构建")
    void build_nullOptionalParams() {
        LlmModel config = chatConfig();
        config.setTemperature(null);
        config.setMaxContextTokens(null);

        assertThat(new OpenAiModelFactory().build(config)).isInstanceOf(OpenAiChatModel.class);
    }

    @Test
    @DisplayName("buildStreaming - 构建 OpenAiStreamingChatModel")
    void buildStreaming_openAiStreamingChatModel() {
        assertThat(new OpenAiModelFactory().buildStreaming(chatConfig())).isInstanceOf(OpenAiStreamingChatModel.class);
        assertThat(new DeepSeekModelFactory().buildStreaming(chatConfig())).isInstanceOf(OpenAiStreamingChatModel.class);
        assertThat(new CustomOpenAiModelFactory().buildStreaming(chatConfig())).isInstanceOf(OpenAiStreamingChatModel.class);
    }

    @Test
    @DisplayName("buildEmbedding - OPENAI/CUSTOM_OPENAI 构建 OpenAiEmbeddingModel")
    void buildEmbedding_openAiEmbeddingModel() {
        assertThat(new OpenAiModelFactory().buildEmbedding(embeddingConfig("OPENAI")))
                .isInstanceOf(OpenAiEmbeddingModel.class);
        assertThat(new CustomOpenAiModelFactory().buildEmbedding(embeddingConfig("CUSTOM_OPENAI")))
                .isInstanceOf(OpenAiEmbeddingModel.class);
    }

    @Test
    @DisplayName("buildEmbedding - DEEPSEEK 不支持嵌入，抛 BusinessException")
    void buildEmbedding_deepSeekUnsupported() {
        assertThatThrownBy(() -> new DeepSeekModelFactory().buildEmbedding(embeddingConfig("DEEPSEEK")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不支持的嵌入模型提供商: DEEPSEEK");
    }
}
