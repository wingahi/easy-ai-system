package com.wgh.rag.kb.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.wgh.rag.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 知识库文件实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wgh_knowledge_file")
public class KnowledgeFile extends BaseEntity {

    /**
     * 知识库ID
     */
    private Long knowledgeBaseId;

    /**
     * 原始文件名
     */
    private String fileName;

    /**
     * 文件类型（扩展名）
     */
    private String fileType;

    /**
     * 文件大小（字节）
     */
    private Long fileSize;

    /**
     * 文件存储路径
     */
    private String filePath;

    /**
     * 分割策略：CHAPTER / PARAGRAPH / LENGTH / CUSTOM
     */
    private String splitStrategy;

    /**
     * 分割策略配置（JSON）
     */
    private String splitConfig;

    /**
     * 分割段落数量
     */
    private Integer chunkCount;

    /**
     * 处理状态：PENDING / PROCESSING / SUCCESS / FAILED
     */
    private String status;

    /**
     * 错误信息
     */
    private String errorMessage;
}
