package com.wgh.rag.quota.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.wgh.rag.data.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 用户周期配额用量（按 periodKey 聚合：日=yyyy-MM-dd，月=yyyy-MM）
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wgh_quota_usage")
public class QuotaUsage extends BaseEntity {

    /**
     * 用户名
     */
    private String username;

    /**
     * 周期键（日配额为 yyyy-MM-dd，月配额为 yyyy-MM；历史数据均为 yyyy-MM-dd）
     */
    private String dateKey;

    /**
     * 当期已用对话条数
     */
    private Integer chatCount;

    /**
     * 当期已用 token 数
     */
    private Long tokenUsed;

    /**
     * 计数纪元：管理端「重置用量」时 +1。
     * <p>扣费流水会快照该值，退款只回退同一纪元的计数，避免重置后误退掉新产生的扣费。</p>
     */
    private Long usageEpoch;
}
