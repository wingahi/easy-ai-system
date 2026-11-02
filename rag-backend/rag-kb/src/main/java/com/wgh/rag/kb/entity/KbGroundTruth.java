package com.wgh.rag.kb.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.wgh.rag.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 检索评测标注（ground truth）：一个查询 + 该查询在知识库中的相关 chunk 集合
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wgh_kb_ground_truth")
public class KbGroundTruth extends BaseEntity {

    /**
     * 知识库 ID
     */
    private Long knowledgeBaseId;

    /**
     * 评测查询
     */
    private String queryText;

    /**
     * 相关 chunk 键集合 JSON 数组，元素形如 "fileId:chunkIndex"
     */
    private String relevantChunks;
}
