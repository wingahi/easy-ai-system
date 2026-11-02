package com.wgh.rag.common.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 用户每日配额用量
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
     * 日期键（yyyy-MM-dd）
     */
    private String dateKey;

    /**
     * 当日已用对话条数
     */
    private Integer chatCount;

    /**
     * 当日已用 token 数
     */
    private Long tokenUsed;
}
