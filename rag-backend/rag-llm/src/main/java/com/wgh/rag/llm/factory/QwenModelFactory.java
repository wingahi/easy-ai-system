package com.wgh.rag.llm.factory;

import org.springframework.stereotype.Component;

/**
 * QWEN provider 工厂（DASHSCOPE 的别名，构建逻辑完全一致）
 */
@Component
public class QwenModelFactory extends DashScopeModelFactory {

    @Override
    public String supportedProvider() {
        return "QWEN";
    }
}
