package com.wgh.rag.llm.factory;

import org.springframework.stereotype.Component;

/**
 * ZHIPU（智谱 GLM）provider 工厂：OpenAI 兼容端点，支持对话/流式/嵌入。
 * 默认端点 https://open.bigmodel.cn/api/paas/v4。
 */
@Component
public class ZhipuModelFactory extends AbstractOpenAiCompatibleModelFactory {

    private static final String DEFAULT_BASE_URL = "https://open.bigmodel.cn/api/paas/v4";

    @Override
    public String supportedProvider() {
        return "ZHIPU";
    }

    @Override
    protected String defaultBaseUrl() {
        return DEFAULT_BASE_URL;
    }
}
