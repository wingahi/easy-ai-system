package com.wgh.rag.kb.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.wgh.rag.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 知识库实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wgh_knowledge_base")
public class KnowledgeBase extends BaseEntity {

    /**
     * 知识库名称
     */
    private String name;

    /**
     * 描述
     */
    private String description;

    /**
     * 嵌入模型ID
     */
    private Long embeddingModelId;

    /**
     * 向量维度
     */
    private Integer dimension;

    /**
     * pgvector 向量表名称
     */
    private String collectionName;

    /**
     * 文件数量
     */
    private Integer fileCount;

    /**
     * 段落数量
     */
    private Integer chunkCount;

    /**
     * 状态：0-禁用，1-启用
     */
    private Integer status;
}
