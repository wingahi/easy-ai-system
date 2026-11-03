package com.wgh.rag.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wgh.rag.auth.dto.LocalLoginResult;
import com.wgh.rag.auth.dto.UserResult;
import com.wgh.rag.auth.security.LocalAuthProperties;
import com.wgh.rag.auth.security.LocalTokenService;
import com.wgh.rag.auth.entity.WghUser;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.auth.mapper.RolePermissionMapper;
import com.wgh.rag.auth.mapper.UserMapper;
import com.wgh.rag.auth.mapper.UserRoleMapper;
import com.wgh.rag.common.result.ResultCode;
import com.wgh.rag.common.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 本地账号密码登录（仅 {@code wgh_user.source = local} 的本地管理员 / 体验账号）。
 * <p>
 * 外部 OIDC 用户不允许走本地密码登录；权限始终按数据库 role → permission 实时解析。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LocalLoginService {

    public static final String SOURCE_LOCAL = "local";
    public static final String ADMIN_ROLE = "admin";

    private final UserMapper userMapper;
    private final UserRoleMapper userRoleMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final PasswordEncoder passwordEncoder;
    private final LocalTokenService localTokenService;
    private final LocalAuthProperties localAuthProperties;
    private final AccountAuditService accountAuditService;

    public LocalLoginResult login(String username, String password) {
        if (!localAuthProperties.isEnabled()) {
            throw new BusinessException(ResultCode.LOGIN_FAILED, "本地账号登录已禁用");
        }
        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "用户名和密码不能为空");
        }

        String name = username.trim();
        WghUser user = userMapper.selectOne(new LambdaQueryWrapper<WghUser>()
                .eq(WghUser::getUsername, name)
                .last("LIMIT 1"));
        if (user == null) {
            log.warn("本地登录失败：用户不存在 username={}", name);
            failLogin(name, "用户不存在");
            throw new BusinessException(ResultCode.LOGIN_FAILED, "用户名或密码错误");
        }
        if (!isLocalSource(user)) {
            log.warn("本地登录被拒：非本地账号 username={}, source={}", name, user.getSource());
            failLogin(name, "非本地来源账号");
            throw new BusinessException(ResultCode.LOGIN_FAILED, "该账号仅支持外部 OIDC 登录");
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            log.warn("本地登录失败：密码错误 username={}", name);
            failLogin(name, "密码错误");
            throw new BusinessException(ResultCode.LOGIN_FAILED, "用户名或密码错误");
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            log.warn("本地登录被拒：账号已禁用 username={}", name);
            failLogin(name, "账号已禁用");
            throw new BusinessException(ResultCode.LOGIN_FAILED, "账号已禁用，请联系管理员");
        }
        if (user.getExpireAt() != null && LocalDateTime.now().isAfter(user.getExpireAt())) {
            log.warn("本地登录被拒：账号已过期 username={}, expireAt={}", name, user.getExpireAt());
            failLogin(name, "账号已过期");
            throw new BusinessException(ResultCode.LOGIN_FAILED,
                    "账号已过期（有效期至 " + user.getExpireAt() + "）");
        }

        Set<String> roles = loadRoles(user.getId());
        boolean admin = roles.contains(ADMIN_ROLE);
        boolean trial = user.getTrial() != null && user.getTrial() == 1;
        if (!admin && !trial) {
            log.warn("本地登录被拒：非管理员且非体验账号 username={}", name);
            failLogin(name, "非管理员且非体验账号");
            throw new BusinessException(ResultCode.LOGIN_FAILED, "仅本地管理员或体验账号支持密码登录");
        }

        LocalTokenService.IssuedToken issued =
                localTokenService.issue(user.getUsername(), user.getId(), roles, user.getPasswordVersion());
        Set<String> permissions = loadPermissions(user.getId());
        boolean passwordMustChange = user.getPasswordMustChange() != null && user.getPasswordMustChange() == 1;
        accountAuditService.record(AccountAuditService.ACTION_LOGIN_SUCCESS, name, name, "SUCCESS", null);
        log.info("本地登录成功 username={}, roles={}, trial={}, passwordMustChange={}",
                name, roles, trial, passwordMustChange);
        return new LocalLoginResult(
                issued.token(),
                "local",
                issued.expiresInSeconds(),
                passwordMustChange,
                new UserResult(user.getId(), user.getUsername(), roles, permissions, passwordMustChange));
    }

    /**
     * 登录失败审计：调用时尚无认证上下文，操作者记为 anonymous，只记录目标账号与原因
     */
    private void failLogin(String username, String reason) {
        accountAuditService.record(AccountAuditService.ACTION_LOGIN_FAIL, username,
                SecurityUtils.ANONYMOUS, "FAIL", reason);
    }

    /**
     * 严格按来源判定：只有显式 {@code source=local} 的账号才允许密码登录。
     * <p>
     * 不能把 {@code source=null} 当作本地账号：外部 OIDC 用户即使误配了
     * {@code password_hash}（非空）也不会进入本地登录分支，避免"有密码即可登录"的漏洞。
     */
    private boolean isLocalSource(WghUser user) {
        return user.getSource() != null
                && SOURCE_LOCAL.equalsIgnoreCase(user.getSource().trim());
    }

    private Set<String> loadRoles(Long userId) {
        List<String> roleCodes = userRoleMapper.selectRoleCodesByUserId(userId);
        Set<String> roles = new LinkedHashSet<>();
        if (roleCodes != null) {
            roles.addAll(roleCodes);
        }
        if (roles.isEmpty()) {
            roles.add(com.wgh.rag.common.security.LoginUser.DEFAULT_ROLE);
        }
        return roles;
    }

    private Set<String> loadPermissions(Long userId) {
        List<String> permCodes = rolePermissionMapper.selectPermCodesByUserId(userId);
        return permCodes == null ? new LinkedHashSet<>() : new LinkedHashSet<>(permCodes);
    }
}
