package com.wgh.rag.llm.factory;

import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.llm.entity.EmbeddingModelConfig;
import com.wgh.rag.llm.entity.LlmModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.community.model.dashscope.QwenChatModel;
import dev.langchain4j.community.model.dashscope.QwenEmbeddingModel;
import dev.langchain4j.community.model.dashscope.QwenStreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * LLM 模型动态工厂 - 根据数据库配置动态创建 ChatModel / EmbeddingModel
 */
@Slf4j
@Component
public class LlmModelFactory {

    /**
     * 创建 ChatModel
     */
    public ChatModel createChatModel(LlmModel config) {
        return switch (config.getProvider().toUpperCase()) {
            case "OPENAI", "DEEPSEEK", "CUSTOM_OPENAI" -> createOpenAiStyleChatModel(config);
            case "DASHSCOPE", "QWEN" -> createDashScopeChatModel(config);
            default -> throw new BusinessException("不支持的LLM提供商: " + config.getProvider());
        };
    }

    /**
     * 创建 StreamingChatModel（SSE 流式响应用）
     */
    public StreamingChatModel createStreamingChatModel(LlmModel config) {
        return switch (config.getProvider().toUpperCase()) {
            case "OPENAI", "DEEPSEEK", "CUSTOM_OPENAI" -> createOpenAiStyleStreamingChatModel(config);
            case "DASHSCOPE", "QWEN" -> createDashScopeStreamingChatModel(config);
            default -> throw new BusinessException("不支持的LLM提供商: " + config.getProvider());
        };
    }

    /**
     * 创建 EmbeddingModel
     */
    public EmbeddingModel createEmbeddingModel(EmbeddingModelConfig config) {
        return switch (config.getProvider().toUpperCase()) {
            case "OPENAI", "CUSTOM_OPENAI" -> createOpenAiStyleEmbeddingModel(config);
            case "DASHSCOPE", "QWEN" -> createDashScopeEmbeddingModel(config);
            default -> throw new BusinessException("不支持的嵌入模型提供商: " + config.getProvider());
        };
    }

    // ============ OpenAI 风格 ============
    
    private ChatModel createOpenAiStyleChatModel(LlmModel config) {
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

    private StreamingChatModel createOpenAiStyleStreamingChatModel(LlmModel config) {
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

    private EmbeddingModel createOpenAiStyleEmbeddingModel(EmbeddingModelConfig config) {
        return OpenAiEmbeddingModel.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .modelName(config.getModelName())
                .timeout(Duration.ofSeconds(30))
                .logRequests(true)
                .logResponses(true)
                .build();
    }

    // ============ DashScope (通义) ============
    
    private ChatModel createDashScopeChatModel(LlmModel config) {
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

    private StreamingChatModel createDashScopeStreamingChatModel(LlmModel config) {
        return QwenStreamingChatModel.builder()
                .apiKey(config.getApiKey())
                .modelName(config.getModelName())
                .baseUrl(config.getBaseUrl())
                .build();
    }

    private EmbeddingModel createDashScopeEmbeddingModel(EmbeddingModelConfig config) {
        return QwenEmbeddingModel.builder()
                .apiKey(config.getApiKey())
                .modelName(config.getModelName())
                .build();
    }
}
