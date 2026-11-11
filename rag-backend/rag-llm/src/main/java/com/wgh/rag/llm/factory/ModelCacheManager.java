package com.wgh.rag.llm.factory;

import com.wgh.rag.llm.constant.ProviderCatalog;
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
 * LLM 模型缓存管理 - 避免每次请求都重新创建模型实例。
 * <p>
 * 构建前统一做 provider 归一化与能力校验（见 {@link ProviderCatalog}）：
 * 未知 provider、缺失 API Key、对不支持嵌入的 provider 取嵌入模型，都会抛出明确异常，
 * 由上层故障转移组件捕获并切换备用模型。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelCacheManager {

    private final ChatModelFactoryRegistry modelFactoryRegistry;
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
        if (modelId == null) {
            throw new IllegalArgumentException("modelId 不能为 null — 调用方需要指定一个有效的 LLM 模型 ID");
        }
        return chatModelCache.computeIfAbsent(modelId, id -> {
            LlmModel config = llmModelMapper.selectById(id);
            if (config == null) {
                throw new IllegalArgumentException("LLM模型不存在: " + id);
            }
            String provider = ProviderCatalog.normalize(config.getProvider());
            ProviderCatalog.requireChat(provider);
            log.info("创建 ChatModel: {} ({}, provider={})", config.getName(), config.getModelName(), provider);
            return modelFactoryRegistry.get(provider).build(config);
        });
    }

    /**
     * 获取或创建 StreamingChatModel
     */
    public StreamingChatModel getStreamingChatModel(Long modelId) {
        if (modelId == null) {
            throw new IllegalArgumentException("modelId 不能为 null — 调用方需要指定一个有效的 LLM 模型 ID");
        }
        return streamingChatModelCache.computeIfAbsent(modelId, id -> {
            LlmModel config = llmModelMapper.selectById(id);
            if (config == null) {
                throw new IllegalArgumentException("LLM模型不存在: " + id);
            }
            String provider = ProviderCatalog.normalize(config.getProvider());
            ProviderCatalog.requireStreaming(provider);
            log.info("创建 StreamingChatModel: {} ({}, provider={})", config.getName(), config.getModelName(), provider);
            return modelFactoryRegistry.get(provider).buildStreaming(config);
        });
    }

    /**
     * 获取或创建 EmbeddingModel
     */
    public EmbeddingModel getEmbeddingModel(Long embeddingModelId) {
        if (embeddingModelId == null) {
            throw new IllegalArgumentException("modelId 不能为 null — 调用方需要指定一个有效的嵌入模型 ID");
        }
        return embeddingModelCache.computeIfAbsent(embeddingModelId, id -> {
            EmbeddingModelConfig config = embeddingModelMapper.selectById(id);
            if (config == null) {
                throw new IllegalArgumentException("嵌入模型不存在: " + id);
            }
            String provider = ProviderCatalog.normalize(config.getProvider());
            ProviderCatalog.requireEmbedding(provider);
            log.info("创建 EmbeddingModel: {} ({}, provider={})", config.getName(), config.getModelName(), provider);
            return modelFactoryRegistry.getEmbeddingFactory(provider).buildEmbedding(config);
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
