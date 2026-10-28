package com.wgh.rag.llm.factory;

import com.wgh.rag.llm.entity.EmbeddingModelConfig;
import com.wgh.rag.llm.entity.LlmModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * LLM 模型动态工厂 - 根据数据库配置动态创建 ChatModel / EmbeddingModel。
 * 路由与构建已下沉到 {@link ChatModelFactoryRegistry} + 各 provider 的 {@link ChatModelFactory}，
 * 本类保留为兼容门面（公开方法与异常行为不变）。
 */
@Component
public class LlmModelFactory {

    private final ChatModelFactoryRegistry factoryRegistry;

    @Autowired
    public LlmModelFactory(ChatModelFactoryRegistry factoryRegistry) {
        this.factoryRegistry = factoryRegistry;
    }

    /**
     * 无 Spring 上下文时的手动构造（如历史单元测试），内部装配全部内置 provider 工厂
     */
    public LlmModelFactory() {
        this(new ChatModelFactoryRegistry(List.of(
                new OpenAiModelFactory(),
                new DeepSeekModelFactory(),
                new CustomOpenAiModelFactory(),
                new DashScopeModelFactory(),
                new QwenModelFactory())));
    }

    /**
     * 创建 ChatModel
     */
    public ChatModel createChatModel(LlmModel config) {
        return factoryRegistry.get(config.getProvider()).build(config);
    }

    /**
     * 创建 StreamingChatModel（SSE 流式响应用）
     */
    public StreamingChatModel createStreamingChatModel(LlmModel config) {
        return factoryRegistry.get(config.getProvider()).buildStreaming(config);
    }

    /**
     * 创建 EmbeddingModel
     */
    public EmbeddingModel createEmbeddingModel(EmbeddingModelConfig config) {
        return factoryRegistry.getEmbeddingFactory(config.getProvider()).buildEmbedding(config);
    }
}
