package com.wgh.rag.kb.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.result.PageResult;
import com.wgh.rag.common.result.ResultCode;
import com.wgh.rag.llm.entity.EmbeddingModelConfig;
import com.wgh.rag.llm.service.LlmModelService;
import com.wgh.rag.kb.entity.KnowledgeBase;
import com.wgh.rag.kb.entity.KnowledgeFile;
import com.wgh.rag.kb.mapper.KnowledgeBaseMapper;
import com.wgh.rag.kb.mapper.KnowledgeFileMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 知识库管理服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseService {

    private final KnowledgeBaseMapper kbMapper;
    private final KnowledgeFileMapper fileMapper;
    private final LlmModelService llmModelService;
    private final VectorStoreService vectorStoreService;

    public PageResult<KnowledgeBase> page(Long page, Long size, String keyword) {
        LambdaQueryWrapper<KnowledgeBase> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            wrapper.like(KnowledgeBase::getName, keyword)
                    .or().like(KnowledgeBase::getDescription, keyword);
        }
        wrapper.orderByDesc(KnowledgeBase::getCreateTime);
        
        Page<KnowledgeBase> result = kbMapper.selectPage(Page.of(page, size), wrapper);
        return new PageResult<>(result.getTotal(), result.getCurrent(), result.getSize(), result.getRecords());
    }

    public KnowledgeBase getById(Long id) {
        KnowledgeBase kb = kbMapper.selectById(id);
        if (kb == null) {
            throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "知识库不存在");
        }
        return kb;
    }

    @Transactional
    public KnowledgeBase create(KnowledgeBase kb) {
        // 校验嵌入模型
        EmbeddingModelConfig embeddingModel = llmModelService.getEmbeddingModel(kb.getEmbeddingModelId());
        
        kb.setDimension(embeddingModel.getDimension());
        kb.setCollectionName("wgh_kb_" + System.currentTimeMillis());
        kb.setFileCount(0);
        kb.setChunkCount(0);
        kb.setStatus(kb.getStatus() != null ? kb.getStatus() : 1);
        
        kbMapper.insert(kb);
        
        // 创建 pgvector 向量表（Collection 同义）
        vectorStoreService.ensureCollection(kb.getCollectionName(), kb.getDimension());
        
        return kb;
    }

    @Transactional
    public KnowledgeBase update(KnowledgeBase kb) {
        KnowledgeBase existing = kbMapper.selectById(kb.getId());
        if (existing == null) {
            throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "知识库不存在");
        }
        kbMapper.updateById(kb);
        return kb;
    }

    @Transactional
    public void delete(Long id) {
        KnowledgeBase kb = kbMapper.selectById(id);
        if (kb == null) {
            throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "知识库不存在");
        }
        
        // 删除 pgvector 向量表
        try {
            vectorStoreService.dropCollection(kb.getCollectionName());
        } catch (Exception e) {
            log.warn("删除 pgvector 向量表失败: {}", e.getMessage());
        }
        
        // 逻辑删除关联文件
        fileMapper.delete(new LambdaQueryWrapper<KnowledgeFile>()
                .eq(KnowledgeFile::getKnowledgeBaseId, id));
        
        // 逻辑删除知识库
        kbMapper.deleteById(id);
    }

    public List<KnowledgeBase> listAll() {
        return kbMapper.selectList(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getStatus, 1)
                .orderByDesc(KnowledgeBase::getCreateTime));
    }

    /**
     * 更新知识库文件数和段落数
     */
    public void updateCounts(Long kbId) {
        Long fileCount = fileMapper.selectCount(new LambdaQueryWrapper<KnowledgeFile>()
                .eq(KnowledgeFile::getKnowledgeBaseId, kbId));
        
        List<KnowledgeFile> files = fileMapper.selectList(new LambdaQueryWrapper<KnowledgeFile>()
                .eq(KnowledgeFile::getKnowledgeBaseId, kbId));
        int chunkCount = files.stream()
                .mapToInt(f -> f.getChunkCount() != null ? f.getChunkCount() : 0)
                .sum();

        KnowledgeBase update = new KnowledgeBase();
        update.setId(kbId);
        update.setFileCount(fileCount.intValue());
        update.setChunkCount(chunkCount);
        kbMapper.updateById(update);
    }
}
