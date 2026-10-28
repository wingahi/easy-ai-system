package com.wgh.rag.llm;

import com.wgh.rag.llm.entity.LlmModel;
import com.wgh.rag.llm.entity.EmbeddingModelConfig;
import com.wgh.rag.llm.factory.LlmModelFactory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * LlmModelFactory 单元测试 - Mock 不真实调用 API
 * 主要验证：provider 路由正确、异常处理正确
 */
@DisplayName("LLM 模型工厂测试")
class LlmModelFactoryTest {

    private final LlmModelFactory factory = new LlmModelFactory();

    /** 验证不支持的 provider 创建 ChatModel 时抛出 BusinessException 并提示「不支持」。 */
    @Test
    @DisplayName("不支持的 provider 抛 BusinessException")
    void unsupportedProvider_throwsException() {
        LlmModel config = new LlmModel();
        config.setProvider("GOOGLE");
        config.setModelName("test");

        assertThatThrownBy(() -> factory.createChatModel(config))
                .isInstanceOf(com.wgh.rag.common.exception.BusinessException.class)
                .hasMessageContaining("不支持");
    }

    /** 验证 OPENAI provider 能成功创建 ChatModel 实例。 */
    @Test
    @DisplayName("ChatModel - OPENAI provider 路由到 OpenAiChatModel.builder")
    void createChatModel_openai_route() {
        LlmModel config = new LlmModel();
        config.setProvider("OPENAI");
        config.setModelName("gpt-4o-mini");
        config.setBaseUrl("https://api.openai.com/v1");
        config.setApiKey("sk-demo");
        config.setTemperature(0.7);
        config.setMaxContextTokens(4096);

        ChatModel model = factory.createChatModel(config);
        assertThat(model).isNotNull();
    }

    /** 验证 DEEPSEEK provider 按 OpenAI 兼容方式创建 ChatModel 实例。 */
    @Test
    @DisplayName("ChatModel - DEEPSEEK provider (走 OpenAI 兼容)")
    void createChatModel_deepseek_route() {
        LlmModel config = new LlmModel();
        config.setProvider("DEEPSEEK");
        config.setModelName("deepseek-chat");
        config.setBaseUrl("https://api.deepseek.com/v1");
        config.setApiKey("sk-xxx");

        ChatModel model = factory.createChatModel(config);
        assertThat(model).isNotNull();
    }

    /** 验证 CUSTOM_OPENAI provider 按自定义 OpenAI 兼容地址创建 ChatModel 实例。 */
    @Test
    @DisplayName("ChatModel - CUSTOM_OPENAI 自定义 OpenAI 兼容")
    void createChatModel_customOpenAi() {
        LlmModel config = new LlmModel();
        config.setProvider("CUSTOM_OPENAI");
        config.setModelName("my-model");
        config.setBaseUrl("http://localhost:8088/v1");
        config.setApiKey("local-key");

        ChatModel model = factory.createChatModel(config);
        assertThat(model).isNotNull();
    }

    /** 验证 OPENAI provider 能成功创建 StreamingChatModel 实例。 */
    @Test
    @DisplayName("StreamingChatModel - OPENAI 路由到 OpenAiStreamingChatModel")
    void createStreamingChatModel_openai() {
        LlmModel config = new LlmModel();
        config.setProvider("OPENAI");
        config.setModelName("gpt-4o-mini");
        config.setBaseUrl("https://api.openai.com/v1");
        config.setApiKey("sk-demo");

        StreamingChatModel model = factory.createStreamingChatModel(config);
        assertThat(model).isNotNull();
    }

    /** 验证 OPENAI provider 能成功创建 EmbeddingModel 实例。 */
    @Test
    @DisplayName("EmbeddingModel - OPENAI provider")
    void createEmbeddingModel_openai() {
        EmbeddingModelConfig config = new EmbeddingModelConfig();
        config.setProvider("OPENAI");
        config.setModelName("text-embedding-3-small");
        config.setBaseUrl("https://api.openai.com/v1");
        config.setApiKey("sk-demo");

        EmbeddingModel model = factory.createEmbeddingModel(config);
        assertThat(model).isNotNull();
    }

    /** 验证 DASHSCOPE provider 能成功创建 EmbeddingModel 实例。 */
    @Test
    @DisplayName("EmbeddingModel - DASHSCOPE provider")
    void createEmbeddingModel_dashscope() {
        EmbeddingModelConfig config = new EmbeddingModelConfig();
        config.setProvider("DASHSCOPE");
        config.setModelName("text-embedding-v2");
        config.setApiKey("sk-dashscope-demo");

        EmbeddingModel model = factory.createEmbeddingModel(config);
        assertThat(model).isNotNull();
    }

    /** 验证不支持的 provider 创建 EmbeddingModel 时抛出 BusinessException。 */
    @Test
    @DisplayName("EmbeddingModel - 不支持的 provider 抛异常")
    void createEmbeddingModel_unsupported() {
        EmbeddingModelConfig config = new EmbeddingModelConfig();
        config.setProvider("XYZ");
        config.setModelName("embedding");

        assertThatThrownBy(() -> factory.createEmbeddingModel(config))
                .isInstanceOf(com.wgh.rag.common.exception.BusinessException.class);
    }
}
