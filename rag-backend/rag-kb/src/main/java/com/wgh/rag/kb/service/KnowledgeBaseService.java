package com.wgh.rag.kb.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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
import com.wgh.rag.kb.port.VectorStorePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
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
    private final VectorStorePort vectorStoreService;
    private final SemanticCacheService semanticCacheService;
    private final ApplicationEventPublisher eventPublisher;

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

    /**
     * 编辑知识库。
     * <p>
     * 若 embeddingModelId 发生变化，旧向量已处于旧语义空间（维度也可能不同），
     * 不能继续与新模型向量混用：切换到一张全新的 pgvector 表，旧表立即删除，
     * 所有文件标记为 PENDING 并在事务提交后由后台全量重建。
     * 源文件仍在对象存储中，重建失败可重复触发，不会丢数据。
     */
    @Transactional
    public KnowledgeBase update(KnowledgeBase kb) {
        KnowledgeBase existing = kbMapper.selectById(kb.getId());
        if (existing == null) {
            throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "知识库不存在");
        }

        Long newModelId = kb.getEmbeddingModelId();
        boolean modelChanged = newModelId != null && !newModelId.equals(existing.getEmbeddingModelId());
        if (!modelChanged) {
            kbMapper.updateById(kb);
            return kb;
        }

        EmbeddingModelConfig newModel = llmModelService.getEmbeddingModel(newModelId);
        Integer newDimension = newModel.getDimension();
        if (newDimension == null) {
            throw new BusinessException("嵌入模型未配置向量维度: " + newModelId);
        }

        String oldCollection = existing.getCollectionName();
        String newCollection = "wgh_kb_" + existing.getId() + "_" + System.currentTimeMillis();

        // 先确保新表存在，再切换指针：切换前检索仍读旧表，不会读到半成品数据
        vectorStoreService.ensureCollection(newCollection, newDimension);

        kb.setDimension(newDimension);
        kb.setCollectionName(newCollection);
        kb.setChunkCount(0);
        kbMapper.updateById(kb);

        // 旧表从此不再被任何查询引用（检索每次按 kbId 重新读 collectionName）
        if (oldCollection != null) {
            vectorStoreService.dropCollection(oldCollection);
        }

        markFilesPendingForRebuild(existing.getId());
        semanticCacheService.invalidateByKbId(existing.getId());

        eventPublisher.publishEvent(new KbEmbeddingModelChangedEvent(
                existing.getId(), existing.getEmbeddingModelId(), newModelId, oldCollection, newCollection));
        log.warn("知识库 {} embedding 模型切换: {} -> {}，collection {} -> {}，已提交后台重建",
                existing.getId(), existing.getEmbeddingModelId(), newModelId, oldCollection, newCollection);
        return kb;
    }

    /** 换模型后把所有文件标记为待处理，等待后台重建（与知识库指针切换同事务，避免漏标） */
    private void markFilesPendingForRebuild(Long kbId) {
        fileMapper.update(null, new LambdaUpdateWrapper<KnowledgeFile>()
                .eq(KnowledgeFile::getKnowledgeBaseId, kbId)
                .set(KnowledgeFile::getStatus, "PENDING")
                .set(KnowledgeFile::getChunkCount, 0)
                .set(KnowledgeFile::getErrorMessage, null));
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

        // 知识库已删除：清理引用该知识库的窗口语义缓存
        semanticCacheService.invalidateByKbId(id);
        
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
