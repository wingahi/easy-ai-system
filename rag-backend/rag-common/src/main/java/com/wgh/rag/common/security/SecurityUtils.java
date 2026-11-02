package com.wgh.rag.common.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 安全上下文工具：获取当前登录用户名
 */
public final class SecurityUtils {

    public static final String ANONYMOUS = "anonymous";

    private SecurityUtils() {
    }

    /**
     * 当前登录用户名；未登录（如分享链路、异步线程）返回 "anonymous"
     */
    public static String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null || auth.getName().isBlank()) {
            return ANONYMOUS;
        }
        return auth.getName();
    }
}
