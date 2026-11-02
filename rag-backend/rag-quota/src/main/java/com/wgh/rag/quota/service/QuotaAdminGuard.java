package com.wgh.rag.quota.service;

import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.result.ResultCode;
import com.wgh.rag.common.security.LoginUser;
import com.wgh.rag.common.security.SecurityUtils;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 配额管理端权限守卫：仅管理员可操作。
 * <p>
 * 判定规则（兼容不同阶段的认证实现）：LoginUser 角色含 "admin"，
 * 或用户名为内置超级账号 "admin"（RBAC 未落地前的兜底）。
 * 用户名优先取 LoginUser ThreadLocal，缺失时回退 SecurityContext（旧版 JWT 过滤器仅注入用户名）。
 * 待 RBAC 完成后可切换为 @RequiresPermission("quota:manage")。
 */
@Component
public class QuotaAdminGuard {

    private static final String BUILTIN_ADMIN = "admin";
    private static final String ADMIN_ROLE = "admin";

    public void requireAdmin() {
        LoginUser loginUser = LoginUser.get();
        String username = loginUser != null && loginUser.getUsername() != null
                ? loginUser.getUsername()
                : SecurityUtils.currentUsername();
        if (username == null || SecurityUtils.ANONYMOUS.equals(username)) {
            throw new BusinessException(ResultCode.PERMISSION_DENIED, "无权限访问（未登录）");
        }
        Set<String> roles = loginUser == null ? null : loginUser.getRoles();
        boolean isAdmin = BUILTIN_ADMIN.equals(username)
                || (roles != null && roles.contains(ADMIN_ROLE));
        if (!isAdmin) {
            throw new BusinessException(ResultCode.PERMISSION_DENIED, "无权限访问：配额管理仅管理员可用");
        }
    }
}
