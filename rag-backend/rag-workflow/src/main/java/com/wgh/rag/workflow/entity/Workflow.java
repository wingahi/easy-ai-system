package com.wgh.rag.workflow.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.wgh.rag.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 工作流定义 —— 图结构以 graph_json（nodes + edges）整体存储。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wgh_workflow")
public class Workflow extends BaseEntity {

    /** 工作流名称 */
    private String name;

    /** 描述 */
    private String description;

    /** 状态：1 启用 / 0 停用 */
    private Integer status;

    /** 版本号，每次保存 +1 */
    private Integer version;

    /** 图定义 JSON：{ "nodes": [...], "edges": [...] } */
    private String graphJson;
}
