package com.wgh.rag.quota.constant;

/**
 * 配额模块常量：计量维度名（用于指标标签与超限提示）。
 */
public interface QuotaConstants {

    String DIMENSION_CHAT = "chat";
    String DIMENSION_TOKEN = "token";

    /** 扣费流水：已扣未决（执行未确认）。 */
    String CHARGE_CHARGED = "CHARGED";
    /** 扣费流水：执行已开始，扣费生效。 */
    String CHARGE_SETTLED = "SETTLED";
    /** 扣费流水：已退款。 */
    String CHARGE_REFUNDED = "REFUNDED";
    /** 扣费流水：退款失败，等待退避重试。 */
    String CHARGE_REFUND_FAILED = "REFUND_FAILED";
    /** 扣费流水：补偿次数耗尽，等待人工处理。 */
    String CHARGE_DEAD = "DEAD";
}
