package com.wgh.rag.quota.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.wgh.rag.data.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 配额扣费流水（{@code wgh_quota_charge}）。
 *
 * <p>一次对话扣费一行，{@code charge_id} 是退款幂等键。状态机：
 * {@code CHARGED}（已扣未决）→ {@code SETTLED}（已确认产出，扣费生效）；
 * {@code CHARGED} → {@code REFUNDED}（无产出已退款）；
 * {@code CHARGED} → {@code REFUND_FAILED}（退款失败，等待退避重试）→ {@code REFUNDED} / {@code DEAD}（人工退款或核销）。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wgh_quota_charge")
public class QuotaChargeEntity extends BaseEntity {

    /** 扣费标识（UUID），退款幂等键，全局唯一。 */
    private String chargeId;

    /** 用户名。 */
    private String username;

    /** 扣费时的周期键（日=yyyy-MM-dd，月=yyyy-MM），退款按此键回退。 */
    private String periodKey;

    /** 本次扣减的对话条数（当前固定为 1）。 */
    private Integer amount;

    /** 状态：CHARGED / SETTLED / REFUNDED / REFUND_FAILED / DEAD。 */
    private String status;

    /** 关联聊天窗口。 */
    private Long windowId;

    /** 关联会话（会话创建后回填）。 */
    private Long sessionId;

    /** 关联任务标识（预留：后台作业/异步任务接入时使用）。 */
    private String taskId;

    /** 首个有效产出的持久化时间（UTC）；非空表示已产出，自动退款必须跳过。 */
    private LocalDateTime outputAt;

    /** 退款补偿已重试次数。 */
    private Integer retryCount;

    /** 下次可补偿时间（退避）。 */
    private LocalDateTime nextRetryAt;

    /** 最近一次退款失败原因（截断）。 */
    private String lastError;

    /** 扣费时的计数纪元快照；NULL 表示存量流水，退款时不做纪元校验。 */
    private Long usageEpoch;

    /** 终态时间（REFUNDED / DEAD）。 */
    private LocalDateTime finishTime;
}
