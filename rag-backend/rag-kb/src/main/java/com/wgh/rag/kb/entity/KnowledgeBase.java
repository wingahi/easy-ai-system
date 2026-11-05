package com.wgh.rag.kb.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.wgh.rag.data.entity.BaseEntity;
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

    /**
     * 重建状态：''-无 / PENDING-待重建 / RUNNING-重建中 / SUCCEEDED-成功 / FAILED-失败
     */
    private String rebuildStatus;

    /**
     * 重建文件总数（进度分母）
     */
    private Integer rebuildTotal;

    /**
     * 已重建文件数（进度分子）
     */
    private Integer rebuildProcessed;

    /**
     * 重建中的目标向量表（成功后切换为 collectionName，失败时清理）
     */
    private String pendingCollection;

    /**
     * 待生效的 embedding 模型 ID（重建成功后才替换 embeddingModelId）
     */
    private Long pendingEmbeddingModelId;
}
