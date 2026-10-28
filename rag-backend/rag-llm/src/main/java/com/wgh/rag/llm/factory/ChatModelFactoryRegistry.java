package com.wgh.rag.llm.factory;

import com.wgh.rag.common.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Provider 模型工厂注册表——收集容器中全部 {@link ChatModelFactory}，
 * 按 provider（大写）路由。未知 provider 的报错行为与原 LlmModelFactory 的 switch default 分支一致。
 */
@Component
public class ChatModelFactoryRegistry {

    private final Map<String, ChatModelFactory> factories;

    public ChatModelFactoryRegistry(List<ChatModelFactory> factoryList) {
        Map<String, ChatModelFactory> map = new LinkedHashMap<>();
        for (ChatModelFactory factory : factoryList) {
            map.put(factory.supportedProvider().toUpperCase(), factory);
        }
        this.factories = Map.copyOf(map);
    }

    /**
     * 获取聊天模型工厂（ChatModel / StreamingChatModel 共用 provider 集合）
     *
     * @throws BusinessException 不支持的LLM提供商
     */
    public ChatModelFactory get(String provider) {
        ChatModelFactory factory = factories.get(provider.toUpperCase());
        if (factory == null) {
            throw new BusinessException("不支持的LLM提供商: " + provider);
        }
        return factory;
    }

    /**
     * 获取嵌入模型工厂
     *
     * @throws BusinessException 不支持的嵌入模型提供商
     */
    public ChatModelFactory getEmbeddingFactory(String provider) {
        ChatModelFactory factory = factories.get(provider.toUpperCase());
        if (factory == null) {
            throw new BusinessException("不支持的嵌入模型提供商: " + provider);
        }
        return factory;
    }
}
