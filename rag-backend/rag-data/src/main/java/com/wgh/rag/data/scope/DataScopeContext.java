package com.wgh.rag.data.scope;

import com.wgh.rag.common.constant.RoleCodes;
import com.wgh.rag.common.security.LoginUser;
import com.wgh.rag.common.security.SecurityUtils;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 数据权限上下文。
 * <p>
 * 采用“显式开关”：只有需要按可见性过滤的查询（列表/分页）才通过 {@link #runWithScope} 激活，
 * 按 id 精确取数、调度任务、分享链路、Agent/工作流运行等内部查询默认不激活，
 * 因此 {@link DataScopeInterceptor} 不会误伤全局查询。
 * <p>
 * ADMIN 与未登录上下文（内部调用、异步线程、单元测试）直接放行。
 */
public final class DataScopeContext {

    /** 需要数据权限过滤的表 */
    public static final Set<String> SCOPED_TABLES = Set.of(
            "wgh_knowledge_base",
            "wgh_chat_window",
            "wgh_workflow",
            "wgh_tool",
            "wgh_prompt_template");

    private static final ThreadLocal<Scope> HOLDER = new ThreadLocal<>();

    private DataScopeContext() {
    }

    /** 当前生效的数据权限范围；未激活时为 null。 */
    public record Scope(String username, boolean admin) {
    }

    /**
     * 在数据权限范围内执行查询。已激活时复用外层范围，避免嵌套覆盖。
     * ADMIN / 无登录用户不设置范围，直接执行。
     */
    public static <T> T runWithScope(Supplier<T> action) {
        Scope user = resolveUser();
        if (user == null || user.admin() || HOLDER.get() != null) {
            return action.get();
        }
        HOLDER.set(user);
        try {
            return action.get();
        } finally {
            HOLDER.remove();
        }
    }

    public static void runWithScope(Runnable action) {
        runWithScope(() -> {
            action.run();
            return null;
        });
    }

    /** 当前激活的范围（仅列表/分页查询期间非空）。 */
    public static Scope current() {
        return HOLDER.get();
    }

    public static boolean isActive() {
        return HOLDER.get() != null;
    }

    public static void clear() {
        HOLDER.remove();
    }

    /**
     * 解析当前登录用户；未登录（匿名）返回 null。
     * 供归属校验（详情/更新/删除）在非列表场景下使用。
     */
    public static Scope resolveUser() {
        LoginUser loginUser = LoginUser.get();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (loginUser == null && auth != null && auth.getPrincipal() instanceof LoginUser principal) {
            loginUser = principal;
        }

        String username = loginUser != null && StringUtils.hasText(loginUser.getUsername())
                ? loginUser.getUsername()
                : SecurityUtils.currentUsername();
        if (!StringUtils.hasText(username) || SecurityUtils.ANONYMOUS.equals(username)) {
            return null;
        }

        boolean admin = loginUser != null && loginUser.getRoles() != null
                && loginUser.getRoles().stream().anyMatch(RoleCodes.ADMIN::equalsIgnoreCase);
        if (!admin && auth != null && auth.getAuthorities() != null) {
            String adminAuthority = ("ROLE_" + RoleCodes.ADMIN).toLowerCase(Locale.ROOT);
            admin = auth.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .filter(StringUtils::hasText)
                    .anyMatch(a -> adminAuthority.equals(a.toLowerCase(Locale.ROOT)));
        }
        return new Scope(username, admin);
    }
}
