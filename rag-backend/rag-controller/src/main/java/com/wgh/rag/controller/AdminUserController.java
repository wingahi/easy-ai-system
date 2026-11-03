package com.wgh.rag.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wgh.rag.auth.service.LocalLoginService;
import com.wgh.rag.common.entity.WghRole;
import com.wgh.rag.common.entity.WghUser;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.mapper.RoleMapper;
import com.wgh.rag.common.mapper.UserMapper;
import com.wgh.rag.common.mapper.UserRoleMapper;
import com.wgh.rag.common.result.PageResult;
import com.wgh.rag.common.result.Result;
import com.wgh.rag.common.result.ResultCode;
import com.wgh.rag.controller.dto.AdminUserRequest;
import com.wgh.rag.quota.entity.UserQuotaConfig;
import com.wgh.rag.quota.service.QuotaAdminGuard;
import com.wgh.rag.quota.service.QuotaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 体验账号管理接口（仅管理员）。
 * <p>
 * 体验账号与本地管理员共用 {@code wgh_user + wgh_user_role} 模型，来源标记为
 * {@code source=local}、{@code trial=1}；外部 OIDC 用户不可通过该接口管理。
 * 接口禁止创建/提升管理员角色，现有管理员账号也不允许被体验账号逻辑覆盖。
 */
@Slf4j
@Tag(name = "用户管理（体验账号）")
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private static final String ROLE_VIEWER = "viewer";
    private static final String ADMIN_ROLE = LocalLoginService.ADMIN_ROLE;
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9._-]{3,32}$");

    private final UserMapper userMapper;
    private final UserRoleMapper userRoleMapper;
    private final RoleMapper roleMapper;
    private final QuotaService quotaService;
    private final QuotaAdminGuard quotaAdminGuard;
    private final PasswordEncoder passwordEncoder;

    @Operation(summary = "分页列出用户（支持用户名/来源/角色/启用状态筛选）")
    @GetMapping
    public Result<PageResult<Map<String, Object>>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) Boolean enabled) {
        quotaAdminGuard.requireAdmin();

        List<WghUser> users = userMapper.selectList(new LambdaQueryWrapper<WghUser>()
                .orderByAsc(WghUser::getId));
        String kw = StringUtils.hasText(username) ? username.trim() : null;

        List<Map<String, Object>> rows = new ArrayList<>();
        for (WghUser user : users) {
            if (kw != null && !user.getUsername().contains(kw)) {
                continue;
            }
            if (StringUtils.hasText(source) && !source.equalsIgnoreCase(effectiveSource(user))) {
                continue;
            }
            if (enabled != null && (user.getStatus() != null && user.getStatus() == 1) != enabled) {
                continue;
            }
            Set<String> roles = rolesOf(user.getId());
            if (StringUtils.hasText(role) && !roles.contains(role)) {
                continue;
            }
            rows.add(buildRow(user, roles));
        }

        rows.sort(Comparator.comparing(row -> String.valueOf(row.get("username"))));
        long total = rows.size();
        long safePage = Math.max(1, page);
        long safeSize = Math.max(1, size);
        int from = (int) Math.min((safePage - 1) * safeSize, total);
        int to = (int) Math.min(from + safeSize, total);
        return Result.success(new PageResult<>(total, safePage, safeSize,
                new ArrayList<>(rows.subList(from, to))));
    }

    @Operation(summary = "创建体验账号（角色不可为 admin）")
    @PostMapping
    public Result<Map<String, Object>> create(@RequestBody AdminUserRequest request) {
        quotaAdminGuard.requireAdmin();

        String username = request.getUsername() == null ? "" : request.getUsername().trim();
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "用户名需为 3-32 位字母、数字、下划线、点或短横线");
        }
        if (!StringUtils.hasText(request.getPassword())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "密码不能为空");
        }
        if (userMapper.selectCount(new LambdaQueryWrapper<WghUser>()
                .eq(WghUser::getUsername, username)) > 0) {
            throw new BusinessException(ResultCode.RESOURCE_ALREADY_EXISTS, "用户名已存在");
        }

        String role = resolveRole(request.getRole());
        WghUser user = new WghUser();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setSource(LocalLoginService.SOURCE_LOCAL);
        user.setTrial(1);
        user.setStatus(request.getEnabled() == null || request.getEnabled() ? 1 : 0);
        user.setExpireAt(request.getExpireAt());
        user.setRemark(request.getRemark());
        userMapper.insert(user);

        Long roleId = roleMapper.selectOne(new LambdaQueryWrapper<WghRole>()
                .select(WghRole::getId)
                .eq(WghRole::getRoleCode, role)).getId();
        userRoleMapper.insert(user.getId(), roleId);

        applyQuota(username, request);
        log.info("创建体验账号 username={}, role={}, expireAt={}", username, role, request.getExpireAt());
        return Result.success(buildRow(user, rolesOf(user.getId())));
    }

    @Operation(summary = "修改体验账号（角色/启用/备注/有效期/配额）")
    @PutMapping("/{username}")
    public Result<Map<String, Object>> update(@PathVariable String username,
                                              @RequestBody AdminUserRequest request) {
        quotaAdminGuard.requireAdmin();

        WghUser user = requireTrialAccount(username);
        if (StringUtils.hasText(request.getRole())) {
            String role = resolveRole(request.getRole());
            userRoleMapper.deleteByUserId(user.getId());
            Long roleId = roleMapper.selectOne(new LambdaQueryWrapper<WghRole>()
                    .select(WghRole::getId)
                    .eq(WghRole::getRoleCode, role)).getId();
            userRoleMapper.insert(user.getId(), roleId);
        }
        if (request.getEnabled() != null) {
            user.setStatus(request.getEnabled() ? 1 : 0);
        }
        if (request.getRemark() != null) {
            user.setRemark(request.getRemark());
        }
        // 编辑表单会显式提交 expireAt（含 null 表示改为长期），因此直接覆盖
        user.setExpireAt(request.getExpireAt());
        userMapper.updateById(user);

        applyQuota(username, request);
        log.info("修改体验账号 username={}", username);
        return Result.success(buildRow(userMapper.selectById(user.getId()), rolesOf(user.getId())));
    }

    @Operation(summary = "禁用体验账号（选择禁用而非物理删除，保留审计与配额记录）")
    @DeleteMapping("/{username}")
    public Result<Map<String, Object>> disable(@PathVariable String username) {
        quotaAdminGuard.requireAdmin();

        WghUser user = requireTrialAccount(username);
        user.setStatus(0);
        userMapper.updateById(user);
        log.info("禁用体验账号 username={}", username);
        return Result.success(buildRow(userMapper.selectById(user.getId()), rolesOf(user.getId())));
    }

    // ==================== 内部辅助 ====================

    /**
     * 仅允许操作体验账号：source=local、trial=1、且当前角色不含 admin。
     * 管理员账号与外部 OIDC 账号均拒绝，避免体验账号接口被用于提权。
     */
    private WghUser requireTrialAccount(String username) {
        WghUser user = userMapper.selectOne(new LambdaQueryWrapper<WghUser>()
                .eq(WghUser::getUsername, username)
                .last("LIMIT 1"));
        if (user == null) {
            throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "用户不存在");
        }
        if (!LocalLoginService.SOURCE_LOCAL.equalsIgnoreCase(effectiveSource(user))) {
            throw new BusinessException(ResultCode.OPERATION_NOT_ALLOWED,
                    "外部 OIDC 用户不允许通过该接口管理");
        }
        if (user.getTrial() == null || user.getTrial() != 1) {
            throw new BusinessException(ResultCode.OPERATION_NOT_ALLOWED,
                    "仅体验账号允许通过该接口修改");
        }
        if (rolesOf(user.getId()).contains(ADMIN_ROLE)) {
            throw new BusinessException(ResultCode.OPERATION_NOT_ALLOWED,
                    "管理员账号不允许通过体验账号接口修改");
        }
        return user;
    }

    /**
     * 校验角色：必须存在且不允许 admin，避免体验账号被提权。
     */
    private String resolveRole(String role) {
        String target = StringUtils.hasText(role) ? role.trim() : ROLE_VIEWER;
        if (ADMIN_ROLE.equalsIgnoreCase(target)) {
            throw new BusinessException(ResultCode.OPERATION_NOT_ALLOWED,
                    "不允许创建或提升为管理员角色");
        }
        Long exists = roleMapper.selectCount(new LambdaQueryWrapper<WghRole>()
                .eq(WghRole::getRoleCode, target));
        if (exists == null || exists == 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "角色不存在: " + target);
        }
        return target;
    }

    /**
     * 写入/更新体验账号配额（复用 rag-quota 的 per-user 配置；未提供配额字段则不落库，回退全局默认）。
     */
    private void applyQuota(String username, AdminUserRequest request) {
        boolean hasQuota = request.getChatLimit() != null
                || request.getTokenLimit() != null
                || StringUtils.hasText(request.getPeriod())
                || request.getQuotaEnabled() != null;
        if (!hasQuota) {
            return;
        }
        UserQuotaConfig config = new UserQuotaConfig();
        config.setUsername(username);
        config.setChatLimit(request.getChatLimit());
        config.setTokenLimit(request.getTokenLimit());
        config.setPeriod(request.getPeriod());
        config.setEnabled(request.getQuotaEnabled() == null ? Boolean.TRUE : request.getQuotaEnabled());
        config.setRemark("体验账号配额");
        quotaService.saveUserConfig(config);
    }

    private Set<String> rolesOf(Long userId) {
        List<String> roleCodes = userRoleMapper.selectRoleCodesByUserId(userId);
        return roleCodes == null ? Set.of() : new java.util.LinkedHashSet<>(roleCodes);
    }

    private String effectiveSource(WghUser user) {
        return StringUtils.hasText(user.getSource())
                ? user.getSource().toLowerCase() : LocalLoginService.SOURCE_LOCAL;
    }

    private Map<String, Object> buildRow(WghUser user, Set<String> roles) {
        UserQuotaConfig config = quotaService.findUserConfig(user.getUsername());
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", user.getId());
        row.put("username", user.getUsername());
        row.put("source", effectiveSource(user));
        row.put("trial", user.getTrial() != null && user.getTrial() == 1);
        row.put("roles", new ArrayList<>(roles));
        row.put("enabled", user.getStatus() != null && user.getStatus() == 1);
        row.put("expireAt", user.getExpireAt());
        row.put("expired", user.getExpireAt() != null && LocalDateTime.now().isAfter(user.getExpireAt()));
        row.put("remark", user.getRemark());
        row.put("period", config != null ? config.getPeriod() : UserQuotaConfig.PERIOD_DAY);
        row.put("chatLimit", config != null ? config.getChatLimit() : null);
        row.put("tokenLimit", config != null ? config.getTokenLimit() : null);
        row.put("quotaEnabled", config != null && Boolean.TRUE.equals(config.getEnabled()));
        row.put("customQuota", config != null);
        row.put("createTime", user.getCreateTime());
        return row;
    }
}
