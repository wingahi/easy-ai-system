package com.wgh.rag.agent.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("wgh_agent_plan")
public class AgentPlan {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sessionId;
    private Long chatWindowId;
    private Long llmModelId;
    private String status;
    private String userGoal;
    private String planJson;
    private Integer currentStepIndex;
    private Integer maxSteps;
    private Integer totalTokens;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
    private LocalDateTime finishTime;
    @TableField(fill = FieldFill.INSERT)
    private String createBy;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updateBy;
    @TableLogic
    private Integer deleted;
}
