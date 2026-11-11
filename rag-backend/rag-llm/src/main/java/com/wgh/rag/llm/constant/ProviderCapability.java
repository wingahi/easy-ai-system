package com.wgh.rag.llm.constant;

/**
 * 模型提供商能力描述（只读元数据，不落库）。
 *
 * @param provider            提供商标识（大写，见 {@link LlmConstants}）
 * @param displayName         前端展示名称
 * @param description         能力说明（前端下拉/表单提示）
 * @param supportsChat        是否支持同步对话
 * @param supportsStreaming   是否支持流式对话
 * @param supportsEmbedding   是否支持向量嵌入
 * @param supportsRerank      是否支持重排序
 * @param requiresApiKey      是否必须配置 API Key
 * @param defaultBaseUrl      默认 Base URL（可为 null，表示由 SDK/用户填写）
 * @param defaultMaxContextTokens 建议的上下文 Token 上限
 */
public record ProviderCapability(
        String provider,
        String displayName,
        String description,
        boolean supportsChat,
        boolean supportsStreaming,
        boolean supportsEmbedding,
        boolean supportsRerank,
        boolean requiresApiKey,
        String defaultBaseUrl,
        int defaultMaxContextTokens) {
}
