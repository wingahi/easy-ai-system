package com.wgh.rag.agentmulti.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 多 Agent Crew 配置（持久化模板）。
 */
@Data
@TableName("wgh_m_agent_crew")
public class MultiAgentCrew {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;
    private String description;

    /** 执行模式，同 MultiAgentRun.executionMode 枚举 */
    private String executionMode;

    /** Crew 内 agent 定义列表 JSON */
    private String agentsJson;

    /** Graph 模式节点/边 JSON */
    private String graphJson;

    /** Crew 额外配置 JSON */
    private String crewConfigJson;

    /** GroupChat 配置 JSON */
    private String groupChatConfigJson;

    /** 启用状态：1 启用，0 禁用 */
    private Integer status;

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
