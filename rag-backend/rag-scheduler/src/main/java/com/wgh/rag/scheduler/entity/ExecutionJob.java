package com.wgh.rag.scheduler.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.wgh.rag.data.entity.BaseEntity;
import com.wgh.rag.data.handler.JsonStringTypeHandler;
import com.wgh.rag.execution.model.ExecutionLane;
import com.wgh.rag.execution.model.ExecutionStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 后台作业（wgh_execution_job）。
 *
 * <p>跨实例的唯一协调事实源：状态迁移、租约（{@code leaseOwner}/{@code leaseExpireTime}）、
 * 尝试次数与终态都在本表落地。{@code executionId} 是对外暴露的执行标识，与自增主键分离。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wgh_execution_job")
public class ExecutionJob extends BaseEntity {

    /** 对外执行标识（UUID），{@code ExecutionFacade} 的查询/取消/订阅都使用它。 */
    private String executionId;

    /** 提交时的链路 traceId，执行实例恢复该值后可按 traceId 关联提交与执行日志。 */
    private String traceId;

    /** 作业类型，对应 {@code JobHandler#jobType()}。 */
    private String jobType;

    /** 执行车道：BACKGROUND / MAINTENANCE。 */
    private ExecutionLane lane;

    /** 租户标识（单租户场景为 default）。 */
    private String tenantId;

    /** 发起人用户标识；系统任务为空。 */
    private String ownerUserId;

    /** 业务幂等键；同 (jobType, bizKey) 只保留一条记录。 */
    private String bizKey;

    /** 负载 JSON。 */
    @TableField(typeHandler = JsonStringTypeHandler.class)
    private String payloadJson;

    /** 负载结构版本；与当前代码不兼容时置 DEAD(INCOMPATIBLE_PAYLOAD)。 */
    private Integer payloadSchemaVersion;

    /** 优先级，数值越大越优先。 */
    private Integer priority;

    /** 状态：PENDING / LEASED / RUNNING / SUCCEEDED / FAILED / DEAD / CANCELLED。 */
    private ExecutionStatus status;

    /** 期望开始时间。 */
    private LocalDateTime scheduledAt;

    /** 最早可领取时间（重试退避、单飞锁等待后顺延）。 */
    private LocalDateTime notBefore;

    /** 租约持有者实例标识。 */
    private String leaseOwner;

    /** 开始执行时间（首次进入 RUNNING 时写入）。 */
    private LocalDateTime startTime;

    /** 租约过期时间。 */
    private LocalDateTime leaseExpireTime;

    /** 最近一次心跳（续租）时间。 */
    private LocalDateTime heartbeatTime;

    /** 已尝试次数（领取时 +1，首次执行为 1）。 */
    private Integer attempt;

    /** 最大尝试次数。 */
    private Integer maxAttempts;

    /** 单次执行超时秒数；为空时使用车道默认值。 */
    private Integer timeoutSeconds;

    /** 是否已请求取消（0 否 / 1 是）。 */
    private Integer cancelRequested;

    /** 取消原因。 */
    private String cancelReason;

    /** 失败错误码。 */
    private String errorCode;

    /** 失败原因。 */
    private String errorMessage;

    /** 执行结果 JSON（如工作流 runId）。 */
    @TableField(typeHandler = JsonStringTypeHandler.class)
    private String resultJson;

    /** 终态时间。 */
    private LocalDateTime finishTime;

    /** 乐观锁版本，每次状态迁移 +1，便于排查并发接管。 */
    private Integer version;
}
