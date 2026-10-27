package com.wgh.rag.llm.factory;

import com.wgh.rag.llm.entity.EmbeddingModelConfig;
import com.wgh.rag.llm.entity.LlmModel;
import com.wgh.rag.llm.mapper.EmbeddingModelMapper;
import com.wgh.rag.llm.mapper.LlmModelMapper;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LLM 模型缓存管理 - 避免每次请求都重新创建模型实例
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelCacheManager {

    private final LlmModelFactory llmModelFactory;
    private final LlmModelMapper llmModelMapper;
    private final EmbeddingModelMapper embeddingModelMapper;

    // 模型实例缓存
    private final Map<Long, ChatModel> chatModelCache = new ConcurrentHashMap<>();
    private final Map<Long, StreamingChatModel> streamingChatModelCache = new ConcurrentHashMap<>();
    private final Map<Long, EmbeddingModel> embeddingModelCache = new ConcurrentHashMap<>();

    /**
     * 获取或创建 ChatModel
     */
    public ChatModel getChatModel(Long modelId) {
        return chatModelCache.computeIfAbsent(modelId, id -> {
            LlmModel config = llmModelMapper.selectById(id);
            if (config == null) {
                throw new IllegalArgumentException("LLM模型不存在: " + id);
            }
            log.info("创建 ChatModel: {} ({})", config.getName(), config.getModelName());
            return llmModelFactory.createChatModel(config);
        });
    }

    /**
     * 获取或创建 StreamingChatModel
     */
    public StreamingChatModel getStreamingChatModel(Long modelId) {
        return streamingChatModelCache.computeIfAbsent(modelId, id -> {
            LlmModel config = llmModelMapper.selectById(id);
            if (config == null) {
                throw new IllegalArgumentException("LLM模型不存在: " + id);
            }
            log.info("创建 StreamingChatModel: {} ({})", config.getName(), config.getModelName());
            return llmModelFactory.createStreamingChatModel(config);
        });
    }

    /**
     * 获取或创建 EmbeddingModel
     */
    public EmbeddingModel getEmbeddingModel(Long embeddingModelId) {
        return embeddingModelCache.computeIfAbsent(embeddingModelId, id -> {
            EmbeddingModelConfig config = embeddingModelMapper.selectById(id);
            if (config == null) {
                throw new IllegalArgumentException("嵌入模型不存在: " + id);
            }
            log.info("创建 EmbeddingModel: {} ({})", config.getName(), config.getModelName());
            return llmModelFactory.createEmbeddingModel(config);
        });
    }

    /**
     * 清除指定模型缓存（配置变更后调用）
     */
    public void evictChatModel(Long modelId) {
        chatModelCache.remove(modelId);
        streamingChatModelCache.remove(modelId);
        log.info("已清除 ChatModel 缓存: {}", modelId);
    }

    /**
     * 清除指定嵌入模型缓存
     */
    public void evictEmbeddingModel(Long modelId) {
        embeddingModelCache.remove(modelId);
        log.info("已清除 EmbeddingModel 缓存: {}", modelId);
    }
}
