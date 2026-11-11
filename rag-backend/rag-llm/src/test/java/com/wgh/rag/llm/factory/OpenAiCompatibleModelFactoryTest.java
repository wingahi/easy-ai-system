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
 * OpenAI 兼容协议工厂（OPENAI / DEEPSEEK / CUSTOM_OPENAI / AZURE_OPENAI / OLLAMA / ZHIPU）构建行为测试——不真实调用 API。
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

    /** 三个 OpenAI 兼容工厂的 supportedProvider 分别返回 OPENAI、DEEPSEEK、CUSTOM_OPENAI。 */
    @Test
    @DisplayName("supportedProvider 标识正确")
    void supportedProvider() {
        assertThat(new OpenAiModelFactory().supportedProvider()).isEqualTo("OPENAI");
        assertThat(new DeepSeekModelFactory().supportedProvider()).isEqualTo("DEEPSEEK");
        assertThat(new CustomOpenAiModelFactory().supportedProvider()).isEqualTo("CUSTOM_OPENAI");
    }

    /** 三个 OpenAI 兼容工厂的 build 均构建出 OpenAiChatModel。 */
    @Test
    @DisplayName("build - 构建 OpenAiChatModel（含 temperature/maxCompletionTokens 可选参数）")
    void build_openAiChatModel() {
        assertThat(new OpenAiModelFactory().build(chatConfig())).isInstanceOf(OpenAiChatModel.class);
        assertThat(new DeepSeekModelFactory().build(chatConfig())).isInstanceOf(OpenAiChatModel.class);
        assertThat(new CustomOpenAiModelFactory().build(chatConfig())).isInstanceOf(OpenAiChatModel.class);
    }

    /** temperature 与 maxContextTokens 为 null 时仍能构建 OpenAiChatModel。 */
    @Test
    @DisplayName("build - temperature/maxContextTokens 为 null 时走默认分支仍可构建")
    void build_nullOptionalParams() {
        LlmModel config = chatConfig();
        config.setTemperature(null);
        config.setMaxContextTokens(null);

        assertThat(new OpenAiModelFactory().build(config)).isInstanceOf(OpenAiChatModel.class);
    }

    /** 三个 OpenAI 兼容工厂的 buildStreaming 均构建出 OpenAiStreamingChatModel。 */
    @Test
    @DisplayName("buildStreaming - 构建 OpenAiStreamingChatModel")
    void buildStreaming_openAiStreamingChatModel() {
        assertThat(new OpenAiModelFactory().buildStreaming(chatConfig())).isInstanceOf(OpenAiStreamingChatModel.class);
        assertThat(new DeepSeekModelFactory().buildStreaming(chatConfig())).isInstanceOf(OpenAiStreamingChatModel.class);
        assertThat(new CustomOpenAiModelFactory().buildStreaming(chatConfig())).isInstanceOf(OpenAiStreamingChatModel.class);
    }

    /** OPENAI 与 CUSTOM_OPENAI 工厂的 buildEmbedding 返回 OpenAiEmbeddingModel。 */
    @Test
    @DisplayName("buildEmbedding - OPENAI/CUSTOM_OPENAI 构建 OpenAiEmbeddingModel")
    void buildEmbedding_openAiEmbeddingModel() {
        assertThat(new OpenAiModelFactory().buildEmbedding(embeddingConfig("OPENAI")))
                .isInstanceOf(OpenAiEmbeddingModel.class);
        assertThat(new CustomOpenAiModelFactory().buildEmbedding(embeddingConfig("CUSTOM_OPENAI")))
                .isInstanceOf(OpenAiEmbeddingModel.class);
    }

    /** DeepSeek 工厂构建嵌入模型时抛 BusinessException 并提示不支持。 */
    @Test
    @DisplayName("buildEmbedding - DEEPSEEK 不支持嵌入，抛 BusinessException")
    void buildEmbedding_deepSeekUnsupported() {
        assertThatThrownBy(() -> new DeepSeekModelFactory().buildEmbedding(embeddingConfig("DEEPSEEK")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不支持的嵌入模型提供商: DEEPSEEK");
    }

    // ==================== 新增提供商 ====================

    /** 新增三家提供商标识正确。 */
    @Test
    @DisplayName("supportedProvider - AZURE_OPENAI / OLLAMA / ZHIPU")
    void supportedProvider_extended() {
        assertThat(new AzureOpenAiModelFactory().supportedProvider()).isEqualTo("AZURE_OPENAI");
        assertThat(new OllamaModelFactory().supportedProvider()).isEqualTo("OLLAMA");
        assertThat(new ZhipuModelFactory().supportedProvider()).isEqualTo("ZHIPU");
    }

    /** Ollama 无需 Key、无需 Base URL，空配置也能构建对话/流式/嵌入模型。 */
    @Test
    @DisplayName("OLLAMA - 空 Key/Base URL 使用默认端点构建三类模型")
    void ollama_buildWithDefaults() {
        LlmModel config = chatConfig();
        config.setBaseUrl(null);
        config.setApiKey(null);

        assertThat(new OllamaModelFactory().build(config)).isInstanceOf(OpenAiChatModel.class);
        assertThat(new OllamaModelFactory().buildStreaming(config)).isInstanceOf(OpenAiStreamingChatModel.class);
        assertThat(new OllamaModelFactory().buildEmbedding(embeddingConfig("OLLAMA")))
                .isInstanceOf(OpenAiEmbeddingModel.class);
    }

    /** 智谱未填 Base URL 时回落默认端点。 */
    @Test
    @DisplayName("ZHIPU - 未填 Base URL 回落默认端点")
    void zhipu_defaultBaseUrl() {
        LlmModel config = chatConfig();
        config.setProvider("ZHIPU");
        config.setBaseUrl(null);

        assertThat(new ZhipuModelFactory().build(config)).isInstanceOf(OpenAiChatModel.class);
        assertThat(new ZhipuModelFactory().buildEmbedding(embeddingConfig("ZHIPU")))
                .isInstanceOf(OpenAiEmbeddingModel.class);
    }

    /** Azure 缺少 Base URL 时抛出明确错误，便于上层故障转移识别。 */
    @Test
    @DisplayName("AZURE_OPENAI - 缺少 Base URL 抛 BusinessException")
    void azure_missingBaseUrl() {
        LlmModel config = chatConfig();
        config.setProvider("AZURE_OPENAI");
        config.setBaseUrl(null);

        assertThatThrownBy(() -> new AzureOpenAiModelFactory().build(config))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("AZURE_OPENAI 需要配置 Base URL");
    }

    /** Azure 旧版 deployments 路径不被支持，提示改用 v1 端点。 */
    @Test
    @DisplayName("AZURE_OPENAI - 旧版 deployments 路径抛 BusinessException")
    void azure_legacyDeploymentPath() {
        LlmModel config = chatConfig();
        config.setProvider("AZURE_OPENAI");
        config.setBaseUrl("https://demo.openai.azure.com/openai/deployments/gpt-4o");

        assertThatThrownBy(() -> new AzureOpenAiModelFactory().build(config))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请填写 v1 端点");
    }

    /** Azure 资源主机地址自动补全为 /openai/v1，并可构建三类模型。 */
    @Test
    @DisplayName("AZURE_OPENAI - 资源主机自动补全 v1 路径")
    void azure_normalizeHost() {
        LlmModel config = chatConfig();
        config.setProvider("AZURE_OPENAI");
        config.setBaseUrl("https://demo.openai.azure.com/");

        assertThat(new AzureOpenAiModelFactory().build(config)).isInstanceOf(OpenAiChatModel.class);
        assertThat(new AzureOpenAiModelFactory().buildStreaming(config)).isInstanceOf(OpenAiStreamingChatModel.class);

        EmbeddingModelConfig embedding = embeddingConfig("AZURE_OPENAI");
        embedding.setBaseUrl("https://demo.openai.azure.com");
        assertThat(new AzureOpenAiModelFactory().buildEmbedding(embedding))
                .isInstanceOf(OpenAiEmbeddingModel.class);
    }

    /** 需要 Key 的提供商在 Key 为空时抛出明确异常。 */
    @Test
    @DisplayName("缺少 API Key - 需要 Key 的 provider 抛 BusinessException")
    void missingApiKey_requiredProvider() {
        LlmModel config = chatConfig();
        config.setProvider("ZHIPU");
        config.setApiKey("  ");

        assertThatThrownBy(() -> new ZhipuModelFactory().build(config))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("缺少 API Key");
    }

    /** 自定义 OpenAI 兼容端点允许空 Key（本地 vLLM / LM Studio）。 */
    @Test
    @DisplayName("CUSTOM_OPENAI - 允许空 Key")
    void customOpenAi_blankKeyAllowed() {
        LlmModel config = chatConfig();
        config.setProvider("CUSTOM_OPENAI");
        config.setApiKey(null);

        assertThat(new CustomOpenAiModelFactory().build(config)).isInstanceOf(OpenAiChatModel.class);
    }
}
