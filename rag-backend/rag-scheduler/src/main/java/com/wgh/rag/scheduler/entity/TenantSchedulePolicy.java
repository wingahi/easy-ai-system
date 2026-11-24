package com.wgh.rag.scheduler.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.wgh.rag.data.entity.BaseEntity;
import com.wgh.rag.data.handler.JsonStringTypeHandler;
import com.wgh.rag.execution.model.ExecutionLane;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 租户调度策略（wgh_tenant_schedule_policy）。
 *
 * <p>按 {@code (tenant_id, lane)} 唯一；策略只约束准入（并发/排队/权重/时间窗），
 * 不配置线程池大小——池大小是实例级资源。策略变更不追溯已提交作业。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wgh_tenant_schedule_policy")
public class TenantSchedulePolicy extends BaseEntity {

    /** 租户标识；未携带租户上下文时使用匿名桶。 */
    private String tenantId;

    /** 执行车道。 */
    private ExecutionLane lane;

    /** 是否启用；关闭时回退默认策略。 */
    private Integer enabled;

    /** 运行中并发上限（不含排队）。 */
    private Integer maxConcurrent;

    /** 等待中排队上限。 */
    private Integer maxQueue;

    /** 公平调度权重（1-100）。 */
    private Integer weight;

    /** 作业基础优先级加成。 */
    private Integer priorityBoost;

    /** 拒绝策略：REJECT / QUEUE。 */
    private String rejectPolicy;

    /** 排队超时秒数；超时转失败。 */
    private Integer queueTimeoutSeconds;

    /** 每租户每日作业数上限（0=不限）。 */
    private Integer dailyJobLimit;

    /** 每分钟提交上限（0=不限）。 */
    private Integer ratePerMinute;

    /** 允许运行的时间窗 JSON；为空表示全天。 */
    @TableField(typeHandler = JsonStringTypeHandler.class)
    private String timeWindowJson;

    /** 备注。 */
    private String remark;

    /** 乐观锁版本；管理端更新必须携带，避免覆盖并发修改。 */
    private Integer version;
}
