package com.wgh.rag.workflow.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.wgh.rag.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 工作流版本快照 —— 每次保存生成一条，回滚亦生成新版本（只增不改）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wgh_workflow_version")
public class WorkflowVersion extends BaseEntity {

    /** 所属工作流 ID */
    private Long workflowId;

    /** 版本号（与 wgh_workflow.version 对齐） */
    private Integer version;

    /** 保存时的名称快照 */
    private String name;

    /** 保存时的描述快照 */
    private String description;

    /** 保存时的图定义快照 */
    private String graphJson;
}
