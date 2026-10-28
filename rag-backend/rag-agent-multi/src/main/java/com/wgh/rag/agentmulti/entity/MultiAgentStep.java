package com.wgh.rag.agentmulti.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 多 Agent 运行中的单步执行记录。
 * <p>
 * status 枚举值：
 * PENDING / RUNNING / SUCCESS / FAILED / SKIPPED / HITL_PENDING
 */
@Data
@TableName("wgh_m_agent_step")
public class MultiAgentStep {
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联 MultiAgentRun.runId */
    private String runId;

    /** 执行该 step 的 agent 定义 ID */
    private String agentId;

    private Integer stepIndex;

    /** 见类注释枚举 */
    private String status;

    private String role;

    /** 输入上下文（JSONB 字符串） */
    private String inputContext;

    private String outputText;

    private String toolCallsJson;

    private String ragSourcesJson;

    private Double reflectionScore;
    private String reflectionFeedback;

    private String thought;

    /** HITL 等待时的预期人类输入描述 */
    private String hitlExpectedInput;

    private String resultSummary;

    private Integer round;

    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Long durationMs;

    private Integer tokenCount;

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
