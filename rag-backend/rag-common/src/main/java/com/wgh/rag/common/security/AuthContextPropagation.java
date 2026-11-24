package com.wgh.rag.common.security;

import java.util.concurrent.Callable;

/**
 * 登录态与租户上下文的跨线程传递。
 * <p>
 * 在任务提交时捕获 {@link LoginUser} 与 {@link TenantContext}，执行前恢复、执行后还原，
 * 避免线程复用导致租户串号或归属丢失。所有受管线程池（interactive/background/maintenance、
 * {@code @Async}、工作流、Crew）都应通过 TaskDecorator 组合本工具。
 */
public final class AuthContextPropagation {

    private AuthContextPropagation() {
    }

    /** 包装 Runnable：继承提交线程的登录态与租户上下文。 */
    public static Runnable wrap(Runnable task) {
        LoginUser loginUser = LoginUser.get();
        TenantContext.Context tenant = TenantContext.get();
        return () -> {
            LoginUser previousLoginUser = LoginUser.get();
            TenantContext.Context previousTenant = TenantContext.get();
            apply(loginUser, tenant);
            try {
                task.run();
            } finally {
                apply(previousLoginUser, previousTenant);
            }
        };
    }

    /** 包装 Callable：执行期间继承提交线程的登录态与租户上下文。 */
    public static <T> Callable<T> wrap(Callable<T> task) {
        LoginUser loginUser = LoginUser.get();
        TenantContext.Context tenant = TenantContext.get();
        return () -> {
            LoginUser previousLoginUser = LoginUser.get();
            TenantContext.Context previousTenant = TenantContext.get();
            apply(loginUser, tenant);
            try {
                return task.call();
            } finally {
                apply(previousLoginUser, previousTenant);
            }
        };
    }

    private static void apply(LoginUser loginUser, TenantContext.Context tenant) {
        if (loginUser == null) {
            LoginUser.clear();
        } else {
            LoginUser.set(loginUser);
        }
        if (tenant == null) {
            TenantContext.clear();
        } else {
            TenantContext.set(tenant.tenantId(), tenant.userId(), tenant.username());
        }
    }
}
