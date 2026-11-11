package com.wgh.rag.llm.factory;

import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.llm.entity.EmbeddingModelConfig;
import com.wgh.rag.llm.entity.LlmModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;

import java.time.Duration;
import java.util.Map;

/**
 * OpenAI 兼容协议工厂基类（OPENAI / DEEPSEEK / CUSTOM_OPENAI / AZURE_OPENAI / OLLAMA / ZHIPU 共用构建逻辑）。
 * 注意：DASHSCOPE 的 qwen-plus 等模型走 OpenAI 兼容端点时必须配置为 CUSTOM_OPENAI，
 * 与 DashScope 原生端点（{@link DashScopeModelFactory}）的拼接行为不同。
 * <p>
 * 子类可覆写端点/密钥/自定义头钩子，以适配同一协议下的不同厂商（如 Azure 的 api-key 头、Ollama 的免鉴权）。
 */
abstract class AbstractOpenAiCompatibleModelFactory implements ChatModelFactory {

    /** Ollama / 本地兼容端点通常不需要 Key，但 LangChain4j 要求 apiKey 非空 */
    private static final String PLACEHOLDER_API_KEY = "not-required";

    @Override
    public ChatModel build(LlmModel config) {
        var builder = OpenAiChatModel.builder()
                .baseUrl(resolveBaseUrl(config))
                .apiKey(resolveApiKey(config))
                .modelName(config.getModelName())
                .timeout(Duration.ofSeconds(60))
                .logRequests(true)
                .logResponses(true);

        Map<String, String> headers = customHeaders(config);
        if (!headers.isEmpty()) {
            builder.customHeaders(headers);
        }
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
                .baseUrl(resolveBaseUrl(config))
                .apiKey(resolveApiKey(config))
                .modelName(config.getModelName())
                .timeout(Duration.ofSeconds(120))
                .logRequests(true)
                .logResponses(true);

        Map<String, String> headers = customHeaders(config);
        if (!headers.isEmpty()) {
            builder.customHeaders(headers);
        }
        if (config.getTemperature() != null) {
            builder.temperature(config.getTemperature());
        }

        return builder.build();
    }

    @Override
    public EmbeddingModel buildEmbedding(EmbeddingModelConfig config) {
        var builder = OpenAiEmbeddingModel.builder()
                .baseUrl(resolveBaseUrl(config))
                .apiKey(resolveApiKey(config))
                .modelName(config.getModelName())
                .timeout(Duration.ofSeconds(30))
                .logRequests(true)
                .logResponses(true);

        Map<String, String> headers = customHeaders(config);
        if (!headers.isEmpty()) {
            builder.customHeaders(headers);
        }

        return builder.build();
    }

    // ==================== 子类可覆写的适配钩子 ====================

    /** 是否必须配置 API Key（默认必须；Ollama / 自定义本地端点可关闭） */
    protected boolean requiresApiKey() {
        return true;
    }

    /** 默认 Base URL（为空表示由 SDK 默认或用户填写） */
    protected String defaultBaseUrl() {
        return null;
    }

    /** 额外请求头（如 Azure 的 api-key） */
    protected Map<String, String> customHeaders(LlmModel config) {
        return Map.of();
    }

    protected Map<String, String> customHeaders(EmbeddingModelConfig config) {
        return Map.of();
    }

    /** 端点归一化：空值回落默认端点，子类可覆写做路径补全/校验 */
    protected String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return defaultBaseUrl();
        }
        return baseUrl.trim();
    }

    protected String resolveBaseUrl(LlmModel config) {
        return normalizeBaseUrl(config.getBaseUrl());
    }

    protected String resolveBaseUrl(EmbeddingModelConfig config) {
        return normalizeBaseUrl(config.getBaseUrl());
    }

    protected String resolveApiKey(LlmModel config) {
        return requireApiKey(config.getApiKey(), config.getProvider());
    }

    protected String resolveApiKey(EmbeddingModelConfig config) {
        return requireApiKey(config.getApiKey(), config.getProvider());
    }

    private String requireApiKey(String apiKey, String provider) {
        if (!requiresApiKey()) {
            return apiKey == null || apiKey.isBlank() ? PLACEHOLDER_API_KEY : apiKey.trim();
        }
        if (apiKey == null || apiKey.isBlank()) {
            String providerName = provider == null || provider.isBlank() ? supportedProvider() : provider;
            throw new BusinessException("模型提供商 " + providerName + " 缺少 API Key，请在模型管理中补充配置");
        }
        return apiKey.trim();
    }
}
