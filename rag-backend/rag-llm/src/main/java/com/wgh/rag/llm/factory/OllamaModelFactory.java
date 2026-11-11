package com.wgh.rag.llm.factory;

import org.springframework.stereotype.Component;

/**
 * OLLAMA provider 工厂（本地/内网部署，OpenAI 兼容端点 /v1，无需 API Key）。
 * 默认端点 http://localhost:11434/v1，支持对话/流式/嵌入。
 */
@Component
public class OllamaModelFactory extends AbstractOpenAiCompatibleModelFactory {

    private static final String DEFAULT_BASE_URL = "http://localhost:11434/v1";

    @Override
    public String supportedProvider() {
        return "OLLAMA";
    }

    @Override
    protected boolean requiresApiKey() {
        return false;
    }

    @Override
    protected String defaultBaseUrl() {
        return DEFAULT_BASE_URL;
    }
}
