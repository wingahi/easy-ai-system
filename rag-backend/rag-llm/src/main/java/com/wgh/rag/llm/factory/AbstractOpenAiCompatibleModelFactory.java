package com.wgh.rag.llm.factory;

import com.wgh.rag.llm.entity.EmbeddingModelConfig;
import com.wgh.rag.llm.entity.LlmModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;

import java.time.Duration;

/**
 * OpenAI 兼容协议工厂基类（OPENAI / DEEPSEEK / CUSTOM_OPENAI 共用构建逻辑）。
 * 注意：DASHSCOPE 的 qwen-plus 等模型走 OpenAI 兼容端点时必须配置为 CUSTOM_OPENAI，
 * 与 DashScope 原生端点（{@link DashScopeModelFactory}）的拼接行为不同。
 */
abstract class AbstractOpenAiCompatibleModelFactory implements ChatModelFactory {

    @Override
    public ChatModel build(LlmModel config) {
        var builder = OpenAiChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .modelName(config.getModelName())
                .timeout(Duration.ofSeconds(60))
                .logRequests(true)
                .logResponses(true);

        if (config.getTemperature() != null) {
            builder.temperature(config.getTemperature());
        }
        if (config.getMaxContextTokens() != null) {
            builder.maxCompletionTokens(config.getMaxContextTokens());
        }

        return builder.build();
    }

    @Override
    public StreamingChatModel buildStreaming(LlmModel config) {
        var builder = OpenAiStreamingChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .modelName(config.getModelName())
                .timeout(Duration.ofSeconds(120))
                .logRequests(true)
                .logResponses(true);

        if (config.getTemperature() != null) {
            builder.temperature(config.getTemperature());
        }

        return builder.build();
    }

    @Override
    public EmbeddingModel buildEmbedding(EmbeddingModelConfig config) {
        return OpenAiEmbeddingModel.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .modelName(config.getModelName())
                .timeout(Duration.ofSeconds(30))
                .logRequests(true)
                .logResponses(true)
                .build();
    }
}
