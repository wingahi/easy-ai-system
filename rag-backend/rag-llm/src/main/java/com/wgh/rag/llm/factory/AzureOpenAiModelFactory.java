package com.wgh.rag.llm.factory;

import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.llm.entity.EmbeddingModelConfig;
import com.wgh.rag.llm.entity.LlmModel;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * AZURE_OPENAI provider 工厂：复用 OpenAI 兼容协议，适配 Azure v1 端点与 api-key 鉴权头。
 * <p>
 * Base URL 需填写 {@code https://<resource>.openai.azure.com/openai/v1}；
 * 模型名填写 Azure 中的部署名（deployment name）。支持对话/流式/嵌入。
 */
@Component
public class AzureOpenAiModelFactory extends AbstractOpenAiCompatibleModelFactory {

    private static final String API_KEY_HEADER = "api-key";
    private static final String V1_PATH_SUFFIX = "/openai/v1";
    private static final String AZURE_HOST_SUFFIX = ".openai.azure.com";
    /** LangChain4j 要求 apiKey 非空，但 Azure 实际使用 api-key 头鉴权 */
    private static final String BEARER_PLACEHOLDER = "azure-openai-api-key";

    @Override
    public String supportedProvider() {
        return "AZURE_OPENAI";
    }

    @Override
    protected String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new BusinessException(
                    "AZURE_OPENAI 需要配置 Base URL（形如 https://<resource>.openai.azure.com/openai/v1）");
        }
        String trimmed = baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.contains("/openai/deployments/")) {
            throw new BusinessException(
                    "AZURE_OPENAI 不支持旧版 /openai/deployments 路径，请填写 v1 端点（.../openai/v1）");
        }
        if (trimmed.endsWith(AZURE_HOST_SUFFIX)) {
            return trimmed + V1_PATH_SUFFIX;
        }
        return trimmed;
    }

    @Override
    protected String resolveApiKey(LlmModel config) {
        // 只校验 Key 是否存在；真实 Key 通过 api-key 头传递，避免以 Bearer 形式误发
        super.resolveApiKey(config);
        return BEARER_PLACEHOLDER;
    }

    @Override
    protected String resolveApiKey(EmbeddingModelConfig config) {
        super.resolveApiKey(config);
        return BEARER_PLACEHOLDER;
    }

    @Override
    protected Map<String, String> customHeaders(LlmModel config) {
        return apiKeyHeader(config.getApiKey());
    }

    @Override
    protected Map<String, String> customHeaders(EmbeddingModelConfig config) {
        return apiKeyHeader(config.getApiKey());
    }

    private Map<String, String> apiKeyHeader(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return Map.of();
        }
        return Map.of(API_KEY_HEADER, apiKey.trim());
    }
}
