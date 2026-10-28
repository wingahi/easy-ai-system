package com.wgh.rag.llm.factory;

import org.springframework.stereotype.Component;

/**
 * OPENAI provider 工厂
 */
@Component
public class OpenAiModelFactory extends AbstractOpenAiCompatibleModelFactory {

    @Override
    public String supportedProvider() {
        return "OPENAI";
    }
}
