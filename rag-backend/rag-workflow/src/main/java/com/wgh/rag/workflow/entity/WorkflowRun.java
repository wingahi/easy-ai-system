package com.wgh.rag.workflow.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工作流运行实例（一次完整执行）。
 */
@Data
@TableName("wgh_workflow_run")
public class WorkflowRun {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long workflowId;

    /** 运行时的工作流版本快照 */
    private Integer workflowVersion;

    /** 运行输入 */
    private String inputText;

    /** 状态：RUNNING / PAUSED / COMPLETED / FAILED / CANCELLED */
    private String status;

    /** 最终输出 */
    private String outputText;

    /** 运行上下文（变量池 / 节点输出 / 待恢复节点） */
    private String contextJson;

    /** 运行时图快照，保证编辑工作流后旧运行仍可恢复 */
    private String graphSnapshot;

    private String errorMessage;

    private Integer executedNodes;

    private Integer totalNodes;

    private LocalDateTime startTime;

    private LocalDateTime finishTime;

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
