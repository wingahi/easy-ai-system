package com.wgh.rag.kb.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.wgh.rag.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 语义缓存实体（wgh_semantic_cache）
 * 相似问题直接返回缓存答案，跳过检索 + LLM；向量存 wgh_semantic_cache_vec
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wgh_semantic_cache")
public class SemanticCache extends BaseEntity {

    /** 聊天窗口 ID（缓存按窗口隔离） */
    private Long windowId;

    /** 用户问题（截断 2000 字） */
    private String queryText;

    /** 缓存的答案全文 */
    private String answerText;

    /** wgh_semantic_cache_vec 中的向量 ID（uuid） */
    private String embeddingId;

    /** 命中次数 */
    private Integer hitCount;
}
