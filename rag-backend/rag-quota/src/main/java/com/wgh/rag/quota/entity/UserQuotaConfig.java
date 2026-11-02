package com.wgh.rag.quota.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.wgh.rag.common.entity.BaseEntity;
import com.wgh.rag.common.handler.BooleanToSmallintTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 按用户配额配置；无记录或禁用时回退到全局默认（wgh.rag.quota.*）
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wgh_user_quota_config")
public class UserQuotaConfig extends BaseEntity {

    /** 周期：日 */
    public static final String PERIOD_DAY = "DAY";
    /** 周期：月 */
    public static final String PERIOD_MONTH = "MONTH";

    /**
     * 用户名（唯一）
     */
    private String username;

    /**
     * 统计周期：DAY=自然日，MONTH=自然月
     */
    private String period;

    /**
     * 请求数上限（<=0 表示不限制）
     */
    private Integer chatLimit;

    /**
     * token 上限（<=0 表示不限制）
     */
    private Long tokenLimit;

    /**
     * 是否启用（禁用时回退全局默认）
     */
    @TableField(typeHandler = BooleanToSmallintTypeHandler.class)
    private Boolean enabled;

    /**
     * 备注
     */
    private String remark;
}
