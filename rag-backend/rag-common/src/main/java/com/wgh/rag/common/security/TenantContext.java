package com.wgh.rag.common.security;

import org.slf4j.MDC;
import org.springframework.util.StringUtils;

import java.util.function.Supplier;

/**
 * 租户上下文（ThreadLocal + MDC）。
 * <p>
 * 租户标识只能来自可信凭证（本地会话、OIDC claim、API Key 归属），禁止从请求参数读取。
 * Web 入口在认证成功后设置，请求结束清理；异步边界必须使用
 * {@link AuthContextPropagation} 在提交时捕获，执行前恢复、finally 清理。
 */
public final class TenantContext {

    /** 单租户部署与存量数据的默认租户编码。 */
    public static final String DEFAULT_TENANT = "default";

    /** 日志 / MDC 中的租户键。 */
    public static final String MDC_TENANT_KEY = "tenantId";

    private static final ThreadLocal<Context> HOLDER = new ThreadLocal<>();

    private TenantContext() {
    }

    /**
     * 当前请求/任务上下文。
     *
     * @param tenantId 租户编码
     * @param userId   登录用户 ID，可为 {@code null}（如开放 API Key）
     * @param username 用户名，可为 {@code null}
     */
    public record Context(String tenantId, Long userId, String username) {
    }

    /** 设置当前上下文；租户为空时回落为默认租户。 */
    public static void set(String tenantId, Long userId, String username) {
        Context context = new Context(normalize(tenantId), userId, username);
        HOLDER.set(context);
        MDC.put(MDC_TENANT_KEY, context.tenantId());
    }

    /** 当前上下文；未设置时返回 {@code null}。 */
    public static Context get() {
        return HOLDER.get();
    }

    /** 当前租户编码；未设置时返回 {@code null}。 */
    public static String currentTenantId() {
        Context context = HOLDER.get();
        return context == null ? null : context.tenantId();
    }

    /** 当前租户编码；未设置时返回默认租户。 */
    public static String tenantIdOrDefault() {
        String tenantId = currentTenantId();
        return StringUtils.hasText(tenantId) ? tenantId : DEFAULT_TENANT;
    }

    public static Long currentUserId() {
        Context context = HOLDER.get();
        return context == null ? null : context.userId();
    }

    public static String currentUsername() {
        Context context = HOLDER.get();
        return context == null ? null : context.username();
    }

    /** 清理当前线程上下文（含 MDC），必须在 finally 中调用。 */
    public static void clear() {
        HOLDER.remove();
        MDC.remove(MDC_TENANT_KEY);
    }

    /** 在指定上下文中执行并恢复原上下文；用于异步/作业 worker。 */
    public static <T> T callWith(Context context, Supplier<T> action) {
        Context previous = HOLDER.get();
        String previousMdc = MDC.get(MDC_TENANT_KEY);
        apply(context);
        try {
            return action.get();
        } finally {
            apply(previous);
            if (previousMdc == null) {
                MDC.remove(MDC_TENANT_KEY);
            } else {
                MDC.put(MDC_TENANT_KEY, previousMdc);
            }
        }
    }

    /** 在指定上下文中执行并恢复原上下文。 */
    public static void runWith(Context context, Runnable action) {
        callWith(context, () -> {
            action.run();
            return null;
        });
    }

    private static void apply(Context context) {
        if (context == null) {
            HOLDER.remove();
            MDC.remove(MDC_TENANT_KEY);
            return;
        }
        HOLDER.set(context);
        MDC.put(MDC_TENANT_KEY, context.tenantId());
    }

    private static String normalize(String tenantId) {
        return StringUtils.hasText(tenantId) ? tenantId.trim() : DEFAULT_TENANT;
    }
}
