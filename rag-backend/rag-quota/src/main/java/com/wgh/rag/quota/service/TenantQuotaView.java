package com.wgh.rag.quota.service;

import java.time.LocalDateTime;

/**
 * 租户配额视图。
 *
 * @param tenantId   租户编码
 * @param custom     是否已有租户自定义配置（false 表示回落到全局默认值）
 * @param period     统计周期 DAY / MONTH
 * @param chatLimit  请求数上限（<=0 不限）
 * @param tokenLimit token 上限（<=0 不限）
 * @param enabled    是否启用
 * @param remark     备注
 * @param updateTime 最近更新时间
 */
public record TenantQuotaView(
        String tenantId,
        boolean custom,
        String period,
        int chatLimit,
        long tokenLimit,
        boolean enabled,
        String remark,
        LocalDateTime updateTime) {
}
