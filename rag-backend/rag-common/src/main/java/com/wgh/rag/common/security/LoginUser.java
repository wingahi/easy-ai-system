package com.wgh.rag.common.security;

import lombok.Data;
import java.util.Set;

/**
 * 当前登录用户上下文
 */
@Data
public class LoginUser {

    private Long userId;
    private String username;
    private Set<String> roles;
    private Set<String> permissions;

    /** 角色缺失时的默认普通用户角色 */
    public static final String DEFAULT_ROLE = "viewer";

    public static final ThreadLocal<LoginUser> CONTEXT = new ThreadLocal<>();

    public static void set(LoginUser user) {
        CONTEXT.set(user);
    }

    public static LoginUser get() {
        return CONTEXT.get();
    }

    public static void clear() {
        CONTEXT.remove();
    }
}
