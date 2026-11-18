package com.wgh.rag.kb.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.wgh.rag.data.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 评测数据集（版本化）：一个知识库下的用例集合，currentVersion 指向当前生效版本
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wgh_eval_dataset")
public class EvalDataset extends BaseEntity {

    /**
     * 知识库 ID
     */
    private Long knowledgeBaseId;

    /**
     * 数据集名称
     */
    private String name;

    /**
     * 数据集说明
     */
    private String description;

    /**
     * 当前版本号（0 表示空数据集；每次用例变更 +1）
     */
    private Integer currentVersion;
}
