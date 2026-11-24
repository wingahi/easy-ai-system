package com.wgh.rag.quota.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.wgh.rag.data.entity.BaseEntity;
import com.wgh.rag.data.handler.BooleanToSmallintTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 租户级配额配置。
 *
 * <p>当前只做配置读写，尚未接入扣费执行链路（用户维度配额优先）；
 * 表不在租户数据面，仅平台管理端访问。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wgh_tenant_quota_config")
public class TenantQuotaConfig extends BaseEntity {

    /** 周期：日 */
    public static final String PERIOD_DAY = "DAY";
    /** 周期：月 */
    public static final String PERIOD_MONTH = "MONTH";

    /** 租户编码（唯一） */
    private String tenantId;

    /** 统计周期：DAY=自然日，MONTH=自然月 */
    private String period;

    /** 请求数上限（<=0 表示不限制） */
    private Integer chatLimit;

    /** token 上限（<=0 表示不限制） */
    private Long tokenLimit;

    /** 是否启用 */
    @TableField(typeHandler = BooleanToSmallintTypeHandler.class)
    private Boolean enabled;

    /** 备注 */
    private String remark;
}
