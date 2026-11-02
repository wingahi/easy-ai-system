package com.wgh.rag.kb.service;

import com.wgh.rag.kb.entity.KnowledgeFileVersion;
import com.wgh.rag.kb.mapper.KnowledgeFileVersionMapper;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 文件版本快照服务：记录历史切分元数据，支持追溯
 * 回滚不在本类：版本记录只存元数据，实际回滚由 KnowledgeFileService 按版本快照的切分策略重切一次
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileVersionService {

    private final KnowledgeFileVersionMapper versionMapper;
    private final KnowledgeFileService knowledgeFileService;

    public List<KnowledgeFileVersion> list(Long fileId) {
        return versionMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<KnowledgeFileVersion>()
                        .eq(KnowledgeFileVersion::getFileId, fileId)
                        .orderByDesc(KnowledgeFileVersion::getVersion));
    }

    /**
     * 手动触发回滚（按指定版本快照的切分策略重切当前文件，生成新版本）
     */
    @Transactional
    public void rollback(Long fileId, Integer targetVersion) {
        KnowledgeFileVersion snapshot = versionMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<KnowledgeFileVersion>()
                        .eq(KnowledgeFileVersion::getFileId, fileId)
                        .eq(KnowledgeFileVersion::getVersion, targetVersion)
                        .last("LIMIT 1"));
        if (snapshot == null) {
            throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "版本快照不存在");
        }
        if (!"SUCCESS".equals(snapshot.getStatus())) {
            throw new BusinessException("仅回滚已完成的版本快照（当前状态: " + snapshot.getStatus() + "）");
        }
        // 复用 KnowledgeFileService.reSplit：传入 snapshot 的 split_strategy / split_config
        knowledgeFileService.reSplit(fileId, snapshot.getSplitStrategy(), snapshot.getSplitConfig());
    }
}
