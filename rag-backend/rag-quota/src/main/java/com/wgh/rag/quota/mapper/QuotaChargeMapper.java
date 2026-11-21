package com.wgh.rag.quota.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wgh.rag.quota.entity.QuotaChargeEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 配额扣费流水 Mapper。
 *
 * <p>退款状态迁移全部使用条件 UPDATE：影响行数为 0 即视为「已被其它事务处理/不可退」，
 * 由数据库唯一键（{@code charge_id}）与状态条件共同保证幂等，不依赖进程内存。</p>
 */
@Mapper
public interface QuotaChargeMapper extends BaseMapper<QuotaChargeEntity> {

    @Select("""
            SELECT * FROM wgh_quota_charge
             WHERE charge_id = #{chargeId} AND deleted = 0
            """)
    QuotaChargeEntity selectByChargeId(@Param("chargeId") String chargeId);

    /**
     * 执行已开始，扣费生效：仅允许从 CHARGED 迁移。
     *
     * @return 1 表示迁移成功；0 表示已结算/已退款/不存在
     */
    @Update("""
            UPDATE wgh_quota_charge
               SET status = 'SETTLED',
                   update_time = CURRENT_TIMESTAMP
             WHERE charge_id = #{chargeId}
               AND status = 'CHARGED'
               AND deleted = 0
            """)
    int markSettled(@Param("chargeId") String chargeId);

    /**
     * 流水置已退：仅允许从 CHARGED / REFUND_FAILED 迁移。
     * <p>必须与计数条件减一在同一个事务内执行。</p>
     *
     * @return 1 表示本次完成退款迁移；0 表示已退/已结算/不存在
     */
    @Update("""
            UPDATE wgh_quota_charge
               SET status = 'REFUNDED',
                   next_retry_at = NULL,
                   last_error = NULL,
                   finish_time = CURRENT_TIMESTAMP,
                   update_time = CURRENT_TIMESTAMP
             WHERE charge_id = #{chargeId}
               AND status IN ('CHARGED', 'REFUND_FAILED')
               AND deleted = 0
            """)
    int markRefunded(@Param("chargeId") String chargeId);

    /**
     * 退款失败：置 REFUND_FAILED 并累计重试次数。
     */
    @Update("""
            UPDATE wgh_quota_charge
               SET status = 'REFUND_FAILED',
                   retry_count = retry_count + 1,
                   next_retry_at = #{nextRetryAt},
                   last_error = #{lastError},
                   update_time = CURRENT_TIMESTAMP
             WHERE charge_id = #{chargeId}
               AND status IN ('CHARGED', 'REFUND_FAILED')
               AND deleted = 0
            """)
    int markRefundFailed(@Param("chargeId") String chargeId,
                         @Param("nextRetryAt") LocalDateTime nextRetryAt,
                         @Param("lastError") String lastError);

    /**
     * 补偿次数耗尽：置 DEAD 等人工处理。
     */
    @Update("""
            UPDATE wgh_quota_charge
               SET status = 'DEAD',
                   retry_count = retry_count + 1,
                   next_retry_at = NULL,
                   last_error = #{lastError},
                   finish_time = CURRENT_TIMESTAMP,
                   update_time = CURRENT_TIMESTAMP
             WHERE charge_id = #{chargeId}
               AND status IN ('CHARGED', 'REFUND_FAILED')
               AND deleted = 0
            """)
    int markDead(@Param("chargeId") String chargeId, @Param("lastError") String lastError);

    /**
     * 回填会话关联（会话在扣费之后创建）。
     */
    @Update("""
            UPDATE wgh_quota_charge
               SET session_id = #{sessionId},
                   update_time = CURRENT_TIMESTAMP
             WHERE charge_id = #{chargeId}
               AND deleted = 0
            """)
    int attachSession(@Param("chargeId") String chargeId, @Param("sessionId") Long sessionId);

    /**
     * 扫描待补偿流水：
     * <ul>
     *   <li>已扣未决：{@code CHARGED} 且超过保护期；</li>
     *   <li>退款失败：{@code REFUND_FAILED} 且已到退避时间。</li>
     * </ul>
     */
    @Select("""
            SELECT * FROM wgh_quota_charge
             WHERE deleted = 0
               AND (
                    (status = 'CHARGED' AND create_time <= #{protectionCutoff})
                    OR (status = 'REFUND_FAILED'
                        AND create_time <= #{protectionCutoff}
                        AND (next_retry_at IS NULL OR next_retry_at <= #{now}))
               )
             ORDER BY create_time ASC
             LIMIT #{limit}
            """)
    List<QuotaChargeEntity> selectDueRefunds(@Param("protectionCutoff") LocalDateTime protectionCutoff,
                                             @Param("now") LocalDateTime now,
                                             @Param("limit") int limit);
}
