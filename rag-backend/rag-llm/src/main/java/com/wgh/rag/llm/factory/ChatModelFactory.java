package com.wgh.rag.llm.factory;

import com.wgh.rag.llm.entity.EmbeddingModelConfig;
import com.wgh.rag.llm.entity.LlmModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;

/**
 * Provider 模型构建工厂（抽象工厂）——每个 provider 一个实现类，
 * 由 {@link ChatModelFactoryRegistry} 按 provider 注册与路由。
 */
public interface ChatModelFactory {

    /**
     * 支持的 provider 标识（如 "OPENAI" / "DASHSCOPE"），路由时按大写匹配。
     */
    String supportedProvider();

    /**
     * 构建 ChatModel
     */
    ChatModel build(LlmModel config);

    /**
     * 构建 StreamingChatModel（SSE 流式响应用）
     */
    StreamingChatModel buildStreaming(LlmModel config);

    /**
     * 构建 EmbeddingModel；不支持嵌入能力的 provider 抛 BusinessException
     */
    EmbeddingModel buildEmbedding(EmbeddingModelConfig config);
}
