package com.wgh.rag.workflow.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.wgh.rag.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 工作流触发器 —— CRON 定时触发 / WEBHOOK 令牌触发。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wgh_workflow_trigger")
public class WorkflowTrigger extends BaseEntity {

    /** 所属工作流 ID */
    private Long workflowId;

    /** 触发类型：CRON / WEBHOOK */
    private String triggerType;

    /** CRON 表达式（6 段：秒 分 时 日 月 周），仅 CRON 类型使用 */
    private String cronExpression;

    /** Webhook 令牌（URL 路径即密钥），仅 WEBHOOK 类型使用 */
    private String webhookToken;

    /** 触发时的默认输入 */
    private String inputText;

    /** 状态：1 启用 / 0 停用 */
    private Integer status;

    /** 最近触发时间 */
    private LocalDateTime lastFireTime;

    /** 累计触发次数 */
    private Integer fireCount;
}
