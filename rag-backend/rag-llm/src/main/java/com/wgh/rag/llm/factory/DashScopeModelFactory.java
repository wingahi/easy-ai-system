package com.wgh.rag.llm.factory;

import com.wgh.rag.llm.entity.EmbeddingModelConfig;
import com.wgh.rag.llm.entity.LlmModel;
import dev.langchain4j.community.model.dashscope.QwenChatModel;
import dev.langchain4j.community.model.dashscope.QwenEmbeddingModel;
import dev.langchain4j.community.model.dashscope.QwenStreamingChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

/**
 * DASHSCOPE provider 工厂（通义，LangChain4j community 包下的 Qwen 实现，走 DashScope 原生端点）
 */
@Component
public class DashScopeModelFactory implements ChatModelFactory {

    @Override
    public String supportedProvider() {
        return "DASHSCOPE";
    }

    @Override
    public ChatModel build(LlmModel config) {
        var builder = QwenChatModel.builder()
                .apiKey(config.getApiKey())
                .modelName(config.getModelName());

        if (config.getBaseUrl() != null) {
            builder.baseUrl(config.getBaseUrl());
        }
        if (config.getTemperature() != null) {
            builder.temperature(config.getTemperature().floatValue());
        }

        return builder.build();
    }

    @Override
    public StreamingChatModel buildStreaming(LlmModel config) {
        return QwenStreamingChatModel.builder()
                .apiKey(config.getApiKey())
                .modelName(config.getModelName())
                .baseUrl(config.getBaseUrl())
                .build();
    }

    @Override
    public EmbeddingModel buildEmbedding(EmbeddingModelConfig config) {
        return QwenEmbeddingModel.builder()
                .apiKey(config.getApiKey())
                .modelName(config.getModelName())
                .build();
    }
}
