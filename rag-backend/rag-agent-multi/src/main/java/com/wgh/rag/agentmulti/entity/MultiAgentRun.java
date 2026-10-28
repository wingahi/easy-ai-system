package com.wgh.rag.agentmulti.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 多 Agent 运行主记录。
 * <p>
 * executionMode 枚举值：
 * CREW_SEQUENTIAL / CREW_HIERARCHICAL / CREW_PARALLEL /
 * GRAPH_ROUTED / GROUP_CHAT_ROUND_ROBIN / GROUP_CHAT_DYNAMIC_ROUTER
 * <p>
 * status 枚举值：
 * PLANNING / RUNNING / PAUSED / COMPLETED / FAILED / CANCELLED
 */
@Data
@TableName("wgh_m_agent_run")
public class MultiAgentRun {
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 外部运行 ID（业务侧追踪用） */
    private String runId;

    private Long sessionId;
    private Long chatWindowId;

    /** 执行模式，见类注释枚举 */
    private String executionMode;

    /** Crew 配置 JSON */
    private String crewConfigJson;

    /** 参与 agent 列表 JSON */
    private String agentsJson;

    private String userGoal;
    private String inputText;
    private String outputText;

    /** 运行状态，见类注释枚举 */
    private String status;

    private Integer currentRound;
    private Integer maxRounds;

    /** Dynamic Router 模式下的路由器模型 */
    private Long dynamicRouterModelId;
    private String dynamicRouterPrompt;

    /** 终止条件描述 */
    private String terminationCriteria;

    /** Summarizer Agent 定义 ID */
    private String summarizerAgentId;

    private String errorMessage;

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
