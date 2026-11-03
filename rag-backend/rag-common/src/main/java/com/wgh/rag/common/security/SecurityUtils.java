package com.wgh.rag.common.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;

/**
 * 安全上下文工具：获取当前登录用户名
 */
public final class SecurityUtils {

    public static final String ANONYMOUS = "anonymous";

    private SecurityUtils() {
    }

    /**
     * 当前登录用户名；未登录（如分享链路、异步线程）返回 "anonymous"
     * <p>
     * 认证过滤器以 {@link LoginUser} 作为 principal，{@link Authentication#getName()} 会退化为
     * principal 的 toString，因此这里优先取 {@code LoginUser.username}。
     */
    public static String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return ANONYMOUS;
        }
        Object principal = auth.getPrincipal();
        if (principal instanceof LoginUser loginUser && StringUtils.hasText(loginUser.getUsername())) {
            return loginUser.getUsername();
        }
        if (!StringUtils.hasText(auth.getName())) {
            return ANONYMOUS;
        }
        return auth.getName();
    }
}
