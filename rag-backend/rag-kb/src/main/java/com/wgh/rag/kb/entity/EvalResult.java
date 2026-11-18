package com.wgh.rag.kb.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.wgh.rag.data.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 评测结果归档：某数据集某版本的一次评测指标与逐查询明细
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wgh_eval_result")
public class EvalResult extends BaseEntity {

    /**
     * 所属数据集 ID
     */
    private Long datasetId;

    /**
     * 评测知识库 ID
     */
    private Long knowledgeBaseId;

    /**
     * 评测时使用的数据集版本号
     */
    private Integer datasetVersion;

    /**
     * 评测使用的 topK
     */
    private Integer topK;

    /**
     * 是否启用混合检索（0 否 / 1 是）
     */
    private Integer hybrid;

    /**
     * 实际参与计算的查询数
     */
    private Integer queryCount;

    /**
     * Recall@K（分母为标注相关集合）
     */
    private Double recallAtK;

    /**
     * Precision@K
     */
    private Double precisionAtK;

    /**
     * MRR@K
     */
    private Double mrr;

    /**
     * NDCG@K
     */
    private Double ndcg;

    /**
     * 查询命中率（topK 至少 1 条相关）
     */
    private Double queryHitRate;

    /**
     * 召回覆盖率（实际返回条数 / topK）
     */
    private Double coverageRate;

    /**
     * 逐查询明细 JSON
     */
    private String detailsJson;
}
