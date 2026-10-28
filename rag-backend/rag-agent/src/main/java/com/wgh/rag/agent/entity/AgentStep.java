package com.wgh.rag.agent.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("wgh_agent_step")
public class AgentStep {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long planId;
    private Integer stepIndex;
    private String stepId;
    private String title;
    private String description;
    private String dependsOn;
    private String status;
    private String thought;
    private String actionTool;
    private String actionInput;
    private String observation;
    private Double reflectionScore;
    private String reflectionFeedback;
    private Integer retryCount;
    private Integer maxRetries;
    private String resultSummary;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer tokenCount;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
    @TableLogic
    private Integer deleted;
}
