package com.wgh.rag.eval.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.wgh.rag.data.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 评测用例版本快照：同一 (datasetId, datasetVersion) 下的用例集合不可变，旧版本只读
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wgh_eval_case")
public class EvalCase extends BaseEntity {

    /**
     * 所属数据集 ID
     */
    private Long datasetId;

    /**
     * 所属数据集版本号
     */
    private Integer datasetVersion;

    /**
     * 评测查询
     */
    private String queryText;

    /**
     * 相关 chunk 键集合 JSON 数组，元素形如 "fileId:chunkIndex"
     */
    private String relevantChunks;
}
