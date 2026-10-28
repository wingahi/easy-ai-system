package com.wgh.rag.llm.factory;

import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.llm.entity.EmbeddingModelConfig;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

/**
 * DEEPSEEK provider 工厂（OpenAI 兼容协议，仅支持聊天，不支持嵌入）
 */
@Component
public class DeepSeekModelFactory extends AbstractOpenAiCompatibleModelFactory {

    @Override
    public String supportedProvider() {
        return "DEEPSEEK";
    }

    @Override
    public EmbeddingModel buildEmbedding(EmbeddingModelConfig config) {
        throw new BusinessException("不支持的嵌入模型提供商: " + config.getProvider());
    }
}
