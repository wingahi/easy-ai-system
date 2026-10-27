package com.wgh.rag.llm.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.result.PageResult;
import com.wgh.rag.common.result.ResultCode;
import com.wgh.rag.llm.entity.EmbeddingModelConfig;
import com.wgh.rag.llm.entity.LlmModel;
import com.wgh.rag.llm.factory.ModelCacheManager;
import com.wgh.rag.llm.mapper.EmbeddingModelMapper;
import com.wgh.rag.llm.mapper.LlmModelMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * LLM 模型管理服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmModelService {

    private final LlmModelMapper llmModelMapper;
    private final EmbeddingModelMapper embeddingModelMapper;
    private final ModelCacheManager modelCacheManager;

    // ==================== ChatModel 管理 ====================

    public PageResult<LlmModel> pageChatModels(Long page, Long size) {
        Page<LlmModel> result = llmModelMapper.selectPage(
                Page.of(page, size),
                new LambdaQueryWrapper<LlmModel>().eq(LlmModel::getModelType, "CHAT")
                        .orderByDesc(LlmModel::getCreateTime));
        return new PageResult<>(result.getTotal(), result.getCurrent(), result.getSize(), result.getRecords());
    }

    public LlmModel getChatModel(Long id) {
        LlmModel model = llmModelMapper.selectById(id);
        if (model == null || !"CHAT".equals(model.getModelType())) {
            throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "模型不存在");
        }
        return model;
    }

    @Transactional
    public LlmModel createChatModel(LlmModel model) {
        model.setModelType("CHAT");
        model.setCompressionModel(model.getCompressionModel() != null ? model.getCompressionModel() : false);
        model.setStatus(model.getStatus() != null ? model.getStatus() : 1);
        
        // 检查是否已存在压缩模型
        if (Boolean.TRUE.equals(model.getCompressionModel())) {
            Long count = llmModelMapper.selectCount(
                    new LambdaQueryWrapper<LlmModel>().eq(LlmModel::getCompressionModel, 1));
            if (count > 0) {
                throw new BusinessException("已存在压缩模型，全局仅能配置一个");
            }
        }
        
        llmModelMapper.insert(model);
        return model;
    }

    @Transactional
    public LlmModel updateChatModel(LlmModel model) {
        LlmModel existing = llmModelMapper.selectById(model.getId());
        if (existing == null) {
            throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "模型不存在");
        }
        
        if (Boolean.TRUE.equals(model.getCompressionModel()) 
                && !Boolean.TRUE.equals(existing.getCompressionModel())) {
            Long count = llmModelMapper.selectCount(
                    new LambdaQueryWrapper<LlmModel>().eq(LlmModel::getCompressionModel, 1));
            if (count > 0) {
                throw new BusinessException("已存在压缩模型，全局仅能配置一个");
            }
        }
        
        llmModelMapper.updateById(model);
        modelCacheManager.evictChatModel(model.getId());
        return model;
    }

    @Transactional
    public void deleteChatModel(Long id) {
        llmModelMapper.deleteById(id);
        modelCacheManager.evictChatModel(id);
    }

    public List<LlmModel> listEnabledChatModels() {
        return llmModelMapper.selectList(
                new LambdaQueryWrapper<LlmModel>()
                        .eq(LlmModel::getModelType, "CHAT")
                        .eq(LlmModel::getStatus, 1));
    }

    /**
     * 查找压缩上下文专用模型（compressionModel=true 的启用 CHAT 模型）。
     * 未配置则返回 null，调用方应 fallback 到当前聊天模型。
     */
    public LlmModel findCompressionModel() {
        return llmModelMapper.selectOne(
                new LambdaQueryWrapper<LlmModel>()
                        .eq(LlmModel::getModelType, "CHAT")
                        .eq(LlmModel::getStatus, 1)
                        .eq(LlmModel::getCompressionModel, true)
                        .orderByAsc(LlmModel::getId)
                        .last("LIMIT 1"));
    }

    // ==================== EmbeddingModel 管理 ====================

    public PageResult<EmbeddingModelConfig> pageEmbeddingModels(Long page, Long size) {
        Page<EmbeddingModelConfig> result = embeddingModelMapper.selectPage(
                Page.of(page, size),
                new LambdaQueryWrapper<EmbeddingModelConfig>().orderByDesc(EmbeddingModelConfig::getCreateTime));
        return new PageResult<>(result.getTotal(), result.getCurrent(), result.getSize(), result.getRecords());
    }

    public EmbeddingModelConfig getEmbeddingModel(Long id) {
        EmbeddingModelConfig model = embeddingModelMapper.selectById(id);
        if (model == null) {
            throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "嵌入模型不存在");
        }
        return model;
    }

    @Transactional
    public EmbeddingModelConfig createEmbeddingModel(EmbeddingModelConfig model) {
        model.setStatus(model.getStatus() != null ? model.getStatus() : 1);
        model.setIsDefault(model.getIsDefault() != null ? model.getIsDefault() : false);
        
        if (Boolean.TRUE.equals(model.getIsDefault())) {
            EmbeddingModelConfig update = new EmbeddingModelConfig();
            update.setIsDefault(false);
            embeddingModelMapper.update(update, 
                    new LambdaQueryWrapper<EmbeddingModelConfig>().eq(EmbeddingModelConfig::getIsDefault, 1));
        }
        
        embeddingModelMapper.insert(model);
        return model;
    }

    @Transactional
    public EmbeddingModelConfig updateEmbeddingModel(EmbeddingModelConfig model) {
        if (Boolean.TRUE.equals(model.getIsDefault())) {
            EmbeddingModelConfig update = new EmbeddingModelConfig();
            update.setIsDefault(false);
            embeddingModelMapper.update(update,
                    new LambdaQueryWrapper<EmbeddingModelConfig>().eq(EmbeddingModelConfig::getIsDefault, 1)
                            .ne(EmbeddingModelConfig::getId, model.getId()));
        }
        embeddingModelMapper.updateById(model);
        modelCacheManager.evictEmbeddingModel(model.getId());
        return model;
    }

    @Transactional
    public void deleteEmbeddingModel(Long id) {
        embeddingModelMapper.deleteById(id);
        modelCacheManager.evictEmbeddingModel(id);
    }

    public List<EmbeddingModelConfig> listEnabledEmbeddingModels() {
        return embeddingModelMapper.selectList(
                new LambdaQueryWrapper<EmbeddingModelConfig>().eq(EmbeddingModelConfig::getStatus, 1));
    }

    public EmbeddingModelConfig getDefaultEmbeddingModel() {
        EmbeddingModelConfig model = embeddingModelMapper.selectOne(
                new LambdaQueryWrapper<EmbeddingModelConfig>().eq(EmbeddingModelConfig::getIsDefault, 1));
        if (model == null) {
            model = embeddingModelMapper.selectOne(
                    new LambdaQueryWrapper<EmbeddingModelConfig>().eq(EmbeddingModelConfig::getStatus, 1).last("LIMIT 1"));
        }
        return model;
    }
}
