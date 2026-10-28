package com.wgh.rag.workflow.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 节点级执行记录 —— 支持运行回放与刷新恢复。
 */
@Data
@TableName("wgh_workflow_node_run")
public class WorkflowNodeRun {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long runId;

    private String nodeId;

    private String nodeType;

    private String nodeName;

    /** PENDING / RUNNING / SUCCESS / FAILED / SKIPPED / HITL_PENDING */
    private String status;

    /** 循环体第几次执行（非循环节点为 0） */
    private Integer iteration;

    private String inputJson;

    private String outputJson;

    private String errorMessage;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private Long durationMs;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableField(fill = FieldFill.INSERT)
    private String createBy;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updateBy;

    @TableLogic
    private Integer deleted;
}
