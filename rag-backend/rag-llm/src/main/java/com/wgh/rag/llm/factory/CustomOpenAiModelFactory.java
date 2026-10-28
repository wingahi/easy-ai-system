package com.wgh.rag.llm.factory;

import org.springframework.stereotype.Component;

/**
 * CUSTOM_OPENAI provider 工厂（任意 OpenAI 兼容端点，含 qwen-plus 的 OpenAI 兼容接入）
 */
@Component
public class CustomOpenAiModelFactory extends AbstractOpenAiCompatibleModelFactory {

    @Override
    public String supportedProvider() {
        return "CUSTOM_OPENAI";
    }
}
