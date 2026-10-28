package com.wgh.rag.llm.factory;

import com.wgh.rag.llm.entity.EmbeddingModelConfig;
import com.wgh.rag.llm.entity.LlmModel;
import dev.langchain4j.community.model.dashscope.QwenChatModel;
import dev.langchain4j.community.model.dashscope.QwenEmbeddingModel;
import dev.langchain4j.community.model.dashscope.QwenStreamingChatModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DashScope（通义）工厂构建行为测试：DASHSCOPE 与别名 QWEN 均走 community 包 Qwen 实现，
 * 端点拼接与 CUSTOM_OPENAI（OpenAI 兼容端点）不同——不真实调用 API。
 */
@DisplayName("DashScope 模型工厂测试")
class DashScopeModelFactoryTest {

    private LlmModel chatConfig() {
        LlmModel config = new LlmModel();
        config.setModelName("qwen-plus");
        config.setApiKey("sk-dashscope-demo");
        config.setTemperature(0.7);
        return config;
    }

    private EmbeddingModelConfig embeddingConfig() {
        EmbeddingModelConfig config = new EmbeddingModelConfig();
        config.setModelName("text-embedding-v2");
        config.setApiKey("sk-dashscope-demo");
        return config;
    }

    @Test
    @DisplayName("supportedProvider 标识正确（QWEN 为 DASHSCOPE 别名）")
    void supportedProvider() {
        assertThat(new DashScopeModelFactory().supportedProvider()).isEqualTo("DASHSCOPE");
        assertThat(new QwenModelFactory().supportedProvider()).isEqualTo("QWEN");
    }

    @Test
    @DisplayName("build - 构建 QwenChatModel（baseUrl 为 null 时不设置，走默认端点）")
    void build_qwenChatModel() {
        assertThat(new DashScopeModelFactory().build(chatConfig())).isInstanceOf(QwenChatModel.class);
        assertThat(new QwenModelFactory().build(chatConfig())).isInstanceOf(QwenChatModel.class);
    }

    @Test
    @DisplayName("build - 显式 baseUrl 时构建 QwenChatModel")
    void build_withBaseUrl() {
        LlmModel config = chatConfig();
        config.setBaseUrl("https://dashscope.aliyuncs.com/api/v1");

        assertThat(new DashScopeModelFactory().build(config)).isInstanceOf(QwenChatModel.class);
    }

    @Test
    @DisplayName("build - temperature 为 null 时走默认分支仍可构建")
    void build_nullTemperature() {
        LlmModel config = chatConfig();
        config.setTemperature(null);

        assertThat(new DashScopeModelFactory().build(config)).isInstanceOf(QwenChatModel.class);
    }

    @Test
    @DisplayName("buildStreaming - 构建 QwenStreamingChatModel（baseUrl 原样透传，含 null）")
    void buildStreaming_qwenStreamingChatModel() {
        assertThat(new DashScopeModelFactory().buildStreaming(chatConfig()))
                .isInstanceOf(QwenStreamingChatModel.class);

        LlmModel config = chatConfig();
        config.setBaseUrl("https://dashscope.aliyuncs.com/api/v1");
        assertThat(new QwenModelFactory().buildStreaming(config))
                .isInstanceOf(QwenStreamingChatModel.class);
    }

    @Test
    @DisplayName("buildEmbedding - 构建 QwenEmbeddingModel")
    void buildEmbedding_qwenEmbeddingModel() {
        assertThat(new DashScopeModelFactory().buildEmbedding(embeddingConfig()))
                .isInstanceOf(QwenEmbeddingModel.class);
        assertThat(new QwenModelFactory().buildEmbedding(embeddingConfig()))
                .isInstanceOf(QwenEmbeddingModel.class);
    }
}
