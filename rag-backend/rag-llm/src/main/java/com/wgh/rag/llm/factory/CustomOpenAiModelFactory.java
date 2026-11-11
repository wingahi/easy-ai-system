package com.wgh.rag.llm.factory;

import org.springframework.stereotype.Component;

/**
 * CUSTOM_OPENAI provider 工厂（任意 OpenAI 兼容端点，含 qwen-plus 的 OpenAI 兼容接入）。
 * 本地/内网兼容端点（vLLM / LM Studio / OneAPI）通常不校验 Key，因此允空 Key。
 */
@Component
public class CustomOpenAiModelFactory extends AbstractOpenAiCompatibleModelFactory {

    @Override
    public String supportedProvider() {
        return "CUSTOM_OPENAI";
    }

    @Override
    protected boolean requiresApiKey() {
        return false;
    }
}
