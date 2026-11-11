package com.wgh.rag.llm.constant;

import com.wgh.rag.common.exception.BusinessException;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 模型提供商能力目录 —— 集中维护 provider 标识、能力标记与默认参数。
 * <p>
 * 与 {@code ChatModelFactoryRegistry} 的分工：本类只回答“这个 provider 支持什么”，
 * 工厂注册表负责“用哪个实现去构建”。二者共用同一套 provider 标识。
 */
public final class ProviderCatalog {

    /** 存量/历史别名 → 规范 provider */
    private static final Map<String, String> ALIASES = Map.of(
            LlmConstants.PROVIDER_CUSTOM_LEGACY, LlmConstants.PROVIDER_CUSTOM_OPENAI,
            "AZURE", LlmConstants.PROVIDER_AZURE_OPENAI,
            "GLM", LlmConstants.PROVIDER_ZHIPU);

    private static final Map<String, ProviderCapability> CAPABILITIES;

    static {
        Map<String, ProviderCapability> map = new LinkedHashMap<>();
        register(map, new ProviderCapability(LlmConstants.PROVIDER_OPENAI, "OpenAI",
                "OpenAI 官方接口，支持对话/流式/嵌入", true, true, true, false,
                true, "https://api.openai.com/v1", 128000));
        register(map, new ProviderCapability(LlmConstants.PROVIDER_DASHSCOPE, "通义 DashScope",
                "通义原生端点，支持对话/流式/嵌入/重排序", true, true, true, true,
                true, null, 131072));
        register(map, new ProviderCapability(LlmConstants.PROVIDER_QWEN, "通义 Qwen",
                "DashScope 的兼容别名，能力与 DASHSCOPE 一致", true, true, true, true,
                true, null, 131072));
        register(map, new ProviderCapability(LlmConstants.PROVIDER_DEEPSEEK, "DeepSeek",
                "OpenAI 兼容协议，仅支持对话/流式，无嵌入", true, true, false, false,
                true, "https://api.deepseek.com/v1", 65536));
        register(map, new ProviderCapability(LlmConstants.PROVIDER_AZURE_OPENAI, "Azure OpenAI",
                "Azure v1 端点，需 Base URL（形如 https://<resource>.openai.azure.com/openai/v1），支持对话/流式/嵌入",
                true, true, true, false, true, null, 128000));
        register(map, new ProviderCapability(LlmConstants.PROVIDER_OLLAMA, "Ollama 本地部署",
                "OpenAI 兼容端点，默认 http://localhost:11434/v1，无需 API Key，支持对话/流式/嵌入",
                true, true, true, false, false, "http://localhost:11434/v1", 32768));
        register(map, new ProviderCapability(LlmConstants.PROVIDER_ZHIPU, "智谱 GLM",
                "OpenAI 兼容端点，默认 https://open.bigmodel.cn/api/paas/v4，支持对话/流式/嵌入",
                true, true, true, false, true, "https://open.bigmodel.cn/api/paas/v4", 128000));
        register(map, new ProviderCapability(LlmConstants.PROVIDER_CUSTOM_OPENAI, "自定义 OpenAI 兼容",
                "任意 OpenAI 兼容端点（vLLM / LM Studio / OneAPI 等），Key 可留空",
                true, true, true, false, false, null, 8192));
        register(map, new ProviderCapability(LlmConstants.PROVIDER_CUSTOM_RERANK, "通用 /rerank",
                "Jina / SiliconFlow 等 /rerank 兼容端点，仅用于重排序", false, false, false, true,
                true, null, 0));
        CAPABILITIES = Collections.unmodifiableMap(map);
    }

    private ProviderCatalog() {
    }

    private static void register(Map<String, ProviderCapability> map, ProviderCapability capability) {
        map.put(capability.provider(), capability);
    }

    /**
     * 归一化 provider：去空格、转大写并展开历史别名（如 CUSTOM → CUSTOM_OPENAI）。
     */
    public static String normalize(String provider) {
        if (provider == null) {
            return null;
        }
        String key = provider.trim().toUpperCase(Locale.ROOT);
        return ALIASES.getOrDefault(key, key);
    }

    public static Optional<ProviderCapability> find(String provider) {
        String normalized = normalize(provider);
        return normalized == null ? Optional.empty() : Optional.ofNullable(CAPABILITIES.get(normalized));
    }

    /** 全部能力定义（不可变，按注册顺序） */
    public static Map<String, ProviderCapability> all() {
        return CAPABILITIES;
    }

    /** 要求 provider 存在且支持对话，否则抛出与工厂注册表一致的错误 */
    public static ProviderCapability requireChat(String provider) {
        ProviderCapability capability = find(provider)
                .orElseThrow(() -> new BusinessException("不支持的LLM提供商: " + provider));
        if (!capability.supportsChat()) {
            throw new BusinessException("不支持的LLM提供商: " + provider);
        }
        return capability;
    }

    /** 要求 provider 存在且支持流式对话 */
    public static ProviderCapability requireStreaming(String provider) {
        ProviderCapability capability = find(provider)
                .orElseThrow(() -> new BusinessException("不支持的LLM提供商: " + provider));
        if (!capability.supportsStreaming()) {
            throw new BusinessException("不支持的LLM提供商: " + provider);
        }
        return capability;
    }

    /** 要求 provider 存在且支持嵌入，否则抛出嵌入专用的错误文案 */
    public static ProviderCapability requireEmbedding(String provider) {
        ProviderCapability capability = find(provider)
                .orElseThrow(() -> new BusinessException("不支持的嵌入模型提供商: " + provider));
        if (!capability.supportsEmbedding()) {
            throw new BusinessException("不支持的嵌入模型提供商: " + provider);
        }
        return capability;
    }
}
