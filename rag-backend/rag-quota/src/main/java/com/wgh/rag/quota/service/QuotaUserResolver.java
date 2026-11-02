package com.wgh.rag.quota.service;

/**
 * 配额用户解析器：决定"当前请求"按哪个用户名计配额。
 * <p>
 * 默认实现 {@link DefaultQuotaUserResolver} 复用现有认证上下文；
 * 认证链路不可用/缺失时可替换为其他实现（如单用户兜底）。
 */
public interface QuotaUserResolver {

    /**
     * 当前请求对应的配额用户名；无登录上下文时返回单用户兜底名（"anonymous"）
     */
    String currentUsername();
}
