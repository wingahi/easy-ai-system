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
 * 判定规则：LoginUser 角色含 "admin"（角色由 OidcAuthenticationFilter 从外部 token roles claim 映射数据库角色），
 * 不再基于用户名做硬编码超级账号绕过。
 */
@Component
public class QuotaAdminGuard {

    private static final String ADMIN_ROLE = "admin";

    public void requireAdmin() {
        LoginUser loginUser = LoginUser.get();
        if (loginUser == null || loginUser.getUsername() == null
                || SecurityUtils.ANONYMOUS.equals(loginUser.getUsername())) {
            throw new BusinessException(ResultCode.PERMISSION_DENIED, "无权限访问（未登录）");
        }
        Set<String> roles = loginUser.getRoles();
        if (roles == null || !roles.contains(ADMIN_ROLE)) {
            throw new BusinessException(ResultCode.PERMISSION_DENIED, "无权限访问：配额管理仅管理员可用");
        }
    }
}
