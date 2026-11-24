package com.wgh.rag.scheduler.policy;

import java.util.Locale;

/**
 * 租户策略拒绝方式。
 */
public enum RejectPolicy {

    /** 并发已满立即拒绝（交互式默认）。 */
    REJECT,

    /** 并发已满允许排队，达到排队上限再拒绝（后台默认）。 */
    QUEUE;

    /** 解析策略值；未知值回退 {@link #REJECT}，避免脏数据放大排队。 */
    public static RejectPolicy parse(String value, RejectPolicy fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
