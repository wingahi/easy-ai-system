package com.wgh.rag.quota.service;

import com.wgh.rag.quota.entity.UserQuotaConfig;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 配额解析结果：用户自定义配置（启用时）优先，否则全局默认。
 */
@Data
@AllArgsConstructor
public class EffectiveQuota {

    /** 来源：自定义配置 */
    public static final String SOURCE_CUSTOM = "CUSTOM";
    /** 来源：全局默认 */
    public static final String SOURCE_DEFAULT = "DEFAULT";

    /**
     * 统计周期：DAY / MONTH
     */
    private String period;

    /**
     * 请求数上限（<=0 不限制）
     */
    private Integer chatLimit;

    /**
     * token 上限（<=0 不限制）
     */
    private Long tokenLimit;

    /**
     * 来源：CUSTOM / DEFAULT
     */
    private String source;

    public static EffectiveQuota of(UserQuotaConfig cfg) {
        return new EffectiveQuota(cfg.getPeriod(), cfg.getChatLimit(), cfg.getTokenLimit(), SOURCE_CUSTOM);
    }

    public static EffectiveQuota ofDefault(Integer chatDailyLimit, Long tokenDailyLimit) {
        return new EffectiveQuota(UserQuotaConfig.PERIOD_DAY, chatDailyLimit, tokenDailyLimit, SOURCE_DEFAULT);
    }
}
