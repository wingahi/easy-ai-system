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
     * embeddingModelId 变化时<b>不自动重建</b>（大库重切是分钟到小时级，且需要可观测/可取消），
     * 只置 rebuild_status=PENDING 并记录待生效模型；实际重建由 POST /api/kb/{id}/rebuild 显式触发。
     * 在此期间 collection_name 与 embedding_model_id 保持成对不变，检索继续使用旧向量（语义自洽）。
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

        if ("RUNNING".equals(existing.getRebuildStatus())) {
            throw new BusinessException(ResultCode.OPERATION_NOT_ALLOWED,
                    "该知识库正在重建向量，禁止切换 embedding 模型");
        }
        EmbeddingModelConfig newModel = llmModelService.getEmbeddingModel(newModelId);
        if (newModel.getDimension() == null) {
            throw new BusinessException("嵌入模型未配置向量维度: " + newModelId);
        }

        kb.setRebuildStatus("PENDING");
        kb.setPendingEmbeddingModelId(newModelId);
        kb.setRebuildTotal(countFiles(kb.getId()));
        kb.setRebuildProcessed(0);
        kbMapper.updateById(kb);
        log.info("知识库 {} embedding 模型待切换: {} -> {}，等待显式重建",
                existing.getId(), existing.getEmbeddingModelId(), newModelId);
        return kb;
    }

    /**
     * 抢占重建任务（原子条件更新：仅当不在 RUNNING 时成功）。
     * <p>
     * 目标向量表每次重建都新建（{@code wgh_kb_<id>_<ts>}），旧 collection 不动；
     * 重建成功后才切换指针并清理旧表，失败则清理半成品目标表、保留旧数据。
     */
    public RebuildPlan claimRebuild(Long kbId) {
        KnowledgeBase kb = kbMapper.selectById(kbId);
        if (kb == null) {
            throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "知识库不存在");
        }
        Long targetModelId = kb.getPendingEmbeddingModelId() != null
                ? kb.getPendingEmbeddingModelId() : kb.getEmbeddingModelId();
        if (targetModelId == null) {
            throw new BusinessException("知识库未配置 embedding 模型，无法重建");
        }
        EmbeddingModelConfig model = llmModelService.getEmbeddingModel(targetModelId);
        Integer dimension = model.getDimension();
        if (dimension == null) {
            throw new BusinessException("嵌入模型未配置向量维度: " + targetModelId);
        }

        int total = countFiles(kbId);
        String targetCollection = "wgh_kb_" + kbId + "_" + System.currentTimeMillis();
        vectorStoreService.ensureCollection(targetCollection, dimension);

        int claimed = kbMapper.update(null, new LambdaUpdateWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getId, kbId)
                .and(w -> w.isNull(KnowledgeBase::getRebuildStatus)
                        .or().ne(KnowledgeBase::getRebuildStatus, "RUNNING"))
                .set(KnowledgeBase::getRebuildStatus, "RUNNING")
                .set(KnowledgeBase::getRebuildTotal, total)
                .set(KnowledgeBase::getRebuildProcessed, 0)
                .set(KnowledgeBase::getPendingCollection, targetCollection)
                .set(KnowledgeBase::getPendingEmbeddingModelId, targetModelId));
        if (claimed == 0) {
            vectorStoreService.dropCollection(targetCollection);
            throw new BusinessException(ResultCode.OPERATION_NOT_ALLOWED,
                    "该知识库已有重建任务在执行，请稍后再试");
        }

        kb.setRebuildStatus("RUNNING");
        kb.setRebuildTotal(total);
        kb.setRebuildProcessed(0);
        kb.setPendingCollection(targetCollection);
        kb.setPendingEmbeddingModelId(targetModelId);
        log.info("知识库 {} 重建任务已受理: 目标表={}, 模型={}, 文件数={}",
                kbId, targetCollection, targetModelId, total);
        return new RebuildPlan(kb, targetCollection, targetModelId, dimension);
    }

    /** 更新重建进度（N/M），供前端轮询 */
    public void updateRebuildProgress(Long kbId, int processed, int total) {
        KnowledgeBase patch = new KnowledgeBase();
        patch.setId(kbId);
        patch.setRebuildProcessed(processed);
        patch.setRebuildTotal(total);
        kbMapper.updateById(patch);
    }

    /**
     * 重建结束：成功则切换 collection/模型指针并清理旧表；失败则清理半成品目标表、旧数据保持可用。
     */
    public void finishRebuild(RebuildPlan plan, int processed, boolean success) {
        KnowledgeBase kb = plan.kb();
        KnowledgeBase patch = new KnowledgeBase();
        patch.setId(kb.getId());
        patch.setRebuildStatus(success ? "SUCCEEDED" : "FAILED");
        patch.setRebuildProcessed(processed);
        if (success) {
            patch.setCollectionName(plan.targetCollection());
            patch.setEmbeddingModelId(plan.targetModelId());
            patch.setDimension(plan.targetDimension());
            patch.setChunkCount(0);
            kbMapper.updateById(patch);
            // 清空待生效字段（updateById 无法写 null）
            kbMapper.update(null, new LambdaUpdateWrapper<KnowledgeBase>()
                    .eq(KnowledgeBase::getId, kb.getId())
                    .set(KnowledgeBase::getPendingCollection, null)
                    .set(KnowledgeBase::getPendingEmbeddingModelId, null));
            String oldCollection = kb.getCollectionName();
            if (oldCollection != null && !oldCollection.equals(plan.targetCollection())) {
                vectorStoreService.dropCollection(oldCollection);
            }
            log.info("知识库 {} 重建成功: collection {} -> {}，已清理旧表",
                    kb.getId(), oldCollection, plan.targetCollection());
        } else {
            kbMapper.updateById(patch);
            // 保留 pending_* 便于重试；半成品目标表直接删除，旧 collection 未动
            vectorStoreService.dropCollection(plan.targetCollection());
            log.warn("知识库 {} 重建失败，已清理目标表 {}，旧数据继续可用",
                    kb.getId(), plan.targetCollection());
        }
        semanticCacheService.invalidateByKbId(kb.getId());
    }

    private int countFiles(Long kbId) {
        Long count = fileMapper.selectCount(new LambdaQueryWrapper<KnowledgeFile>()
                .eq(KnowledgeFile::getKnowledgeBaseId, kbId));
        return count == null ? 0 : count.intValue();
    }

    /** 一次重建的不可变计划：目标表/模型/维度，避免执行过程中被并发修改 */
    public record RebuildPlan(KnowledgeBase kb, String targetCollection,
                              Long targetModelId, Integer targetDimension) {
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
