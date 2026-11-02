package com.wgh.rag.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wgh.rag.common.entity.WghUser;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.entity.WghRole;
import com.wgh.rag.common.mapper.RoleMapper;
import com.wgh.rag.common.mapper.UserMapper;
import com.wgh.rag.common.mapper.UserRoleMapper;
import com.wgh.rag.common.mapper.RolePermissionMapper;
import com.wgh.rag.controller.dto.UserResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.*;

/**
 * RBAC 认证服务
 * <p>
 * 负责用户名密码登录、OIDC 登录（含自动建号 + 默认角色映射），以及角色 / 权限装配。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthRBACService {

    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;
    private final RolePermissionMapper rolePermissionMapper;

    /**
     * OIDC issuer → 默认 role_code 映射。
     * 未命中时走 {@code default} 键兜底。
     */
    public static final Map<String, String> OIDC_ROLE_MAP = new HashMap<>();

    static {
        OIDC_ROLE_MAP.put("default", "viewer");
        // 可按需扩展：OIDC_ROLE_MAP.put("https://accounts.google.com", "editor");
    }

    // ---------- 用户名 / 密码登录 ----------

    /**
     * 用户名密码登录。
     * <p>
     * 先查 wgh_user 表，没查到 fallback 到硬编码 admin/admin；
     * 密码比对暂时用 {@code rawPassword == passwordHash} 过渡，后续升级 BCrypt。
     */
    @Transactional(readOnly = true)
    public UserResult login(String username, String rawPassword) {
        if (!StringUtils.hasText(username) || !StringUtils.hasText(rawPassword)) {
            throw new BusinessException("用户名或密码不能为空");
        }

        WghUser user = userMapper.selectOne(
                new LambdaQueryWrapper<WghUser>().eq(WghUser::getUsername, username));

        if (user == null) {
            // fallback：硬编码 admin/admin（过渡方案，数据库建号后可移除）
            if ("admin".equals(username) && "admin".equals(rawPassword)) {
                return buildHardcodedAdmin();
            }
            throw new BusinessException("用户名或密码错误");
        }

        // 密码校验（过渡方案：直接字符串相等，后续升级 BCrypt）
        if (!Objects.equals(rawPassword, user.getPasswordHash())) {
            throw new BusinessException("用户名或密码错误");
        }
        if (user.getStatus() != null && user.getStatus() != 1) {
            throw new BusinessException("账号已被禁用");
        }

        return assembleUserResult(user);
    }

    /** 根据 userId 构建登录结果（roles + permissions），供 AuthController.me() / JwtAuthenticationFilter 使用 */
    @Transactional(readOnly = true)
    public UserResult buildUserResult(Long userId) {
        if (userId == null) {
            throw new BusinessException("用户不存在");
        }
        WghUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        return assembleUserResult(user);
    }

    // ---------- OIDC 登录 ----------

    /**
     * OIDC 登录（按 issuer + subject 建号 / 登录）。
     * <p>
     * 数据库不存在用户时：按 {@link #OIDC_ROLE_MAP} 给默认 role_code（未命中则 default → viewer），
     * 自动建号并分配角色；已存在则直接装配角色 / 权限返回。
     */
    @Transactional
    public UserResult loginByOidc(String issuer, String subject, String email, String name) {
        if (!StringUtils.hasText(issuer) || !StringUtils.hasText(subject)) {
            throw new BusinessException("OIDC issuer / subject 不能为空");
        }

        WghUser user = userMapper.selectOne(
                new LambdaQueryWrapper<WghUser>()
                        .eq(WghUser::getOidcIssuer, issuer)
                        .eq(WghUser::getOidcSubject, subject));

        if (user == null) {
            user = autoCreateOidcUser(issuer, subject, email, name);
        }

        return assembleUserResult(user);
    }

    // ---------- 内部工具 ----------

    /** 查询用户的角色编码（去重） */
    private Set<String> collectRoles(Long userId) {
        List<String> codes = userRoleMapper.selectRoleCodesByUserId(userId);
        return codes == null ? new HashSet<>() : new HashSet<>(codes);
    }

    /** 查询用户的权限编码（去重，经由 user_role → role_permission → permission） */
    private Set<String> collectPermissions(Long userId) {
        List<String> codes = rolePermissionMapper.selectPermCodesByUserId(userId);
        return codes == null ? new HashSet<>() : new HashSet<>(codes);
    }

    /** 从数据库用户组装完整的登录结果 */
    private UserResult assembleUserResult(WghUser user) {
        Set<String> roles = collectRoles(user.getId());
        Set<String> permissions = collectPermissions(user.getId());
        return new UserResult(user.getId(), user.getUsername(), roles, permissions);
    }

    /** 硬编码 admin 兜底（过渡方案，无数据库记录时使用） */
    private UserResult buildHardcodedAdmin() {
        return new UserResult(
                1L,
                "admin",
                new HashSet<>(Arrays.asList("admin")),
                new HashSet<>(Arrays.asList("*:*")));
    }

    /** 根据 OIDC 信息自动建号并分配默认角色 */
    private WghUser autoCreateOidcUser(String issuer, String subject, String email, String name) {
        // 生成一个稳定的本地用户名：issuer 最后一段 + subject 前 8 位
        String issuerTag = issuer.contains("/")
                ? issuer.substring(issuer.lastIndexOf('/') + 1)
                : issuer;
        String username = (issuerTag + "_" + subject.substring(0, Math.min(subject.length(), 8))).toLowerCase();
        // 防止与现有用户名冲突
        long suffix = 1;
        String finalUsername = username;
        while (userMapper.selectCount(
                new LambdaQueryWrapper<WghUser>().eq(WghUser::getUsername, finalUsername)) > 0) {
            finalUsername = username + "_" + (suffix++);
        }

        WghUser user = new WghUser();
        user.setUsername(finalUsername);
        user.setPasswordHash(""); // OIDC 用户无本地密码
        user.setName(StringUtils.hasText(name) ? name : finalUsername);
        user.setEmail(email);
        user.setOidcIssuer(issuer);
        user.setOidcSubject(subject);
        user.setStatus(1);
        userMapper.insert(user);

        // 分配默认角色
        String roleCode = OIDC_ROLE_MAP.getOrDefault(issuer, OIDC_ROLE_MAP.get("default"));
        bindRoleIfExists(user.getId(), roleCode);

        log.info("OIDC 自动建号：issuer={}, subject={}, username={}, defaultRole={}",
                issuer, subject, finalUsername, roleCode);
        return user;
    }

    /** 如果角色存在，绑定到用户；否则仅记录日志不阻塞建号 */
    private void bindRoleIfExists(Long userId, String roleCode) {
        if (!StringUtils.hasText(roleCode)) {
            return;
        }
        WghRole role = roleMapper.selectOne(
                new LambdaQueryWrapper<WghRole>().eq(WghRole::getRoleCode, roleCode));
        if (role == null) {
            log.warn("绑定默认角色失败：role_code={} 在 wgh_role 表不存在，用户 {} 将无角色", roleCode, userId);
            return;
        }
        userRoleMapper.insert(userId, role.getId());
    }
}
