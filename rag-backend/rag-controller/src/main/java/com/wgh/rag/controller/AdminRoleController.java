package com.wgh.rag.controller;

import com.wgh.rag.auth.service.LocalLoginService;
import com.wgh.rag.auth.entity.WghPermission;
import com.wgh.rag.auth.entity.WghRole;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.auth.mapper.PermissionMapper;
import com.wgh.rag.auth.mapper.RoleMapper;
import com.wgh.rag.auth.mapper.RolePermissionMapper;
import com.wgh.rag.auth.mapper.UserRoleMapper;
import com.wgh.rag.common.result.Result;
import com.wgh.rag.common.result.ResultCode;
import com.wgh.rag.controller.dto.RolePermissionRequest;
import com.wgh.rag.controller.dto.RoleRequest;
import com.wgh.rag.quota.service.QuotaAdminGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 角色与权限管理接口（仅 admin 角色）。
 * <p>
 * 权限校验沿用 {@link QuotaAdminGuard#requireAdmin()}：角色管理属于超级管理员能力，
 * 不依赖某个可被回收的权限码，避免管理员误操作后失去管理入口。
 * <p>
 * 权限变更写入 {@code wgh_role_permission} 后立即生效：认证过滤器每次请求都实时查库，
 * 已登录用户无需重新登录，下一次请求即按新权限判定。
 */
@Slf4j
@Tag(name = "角色权限管理")
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminRoleController {

    /** 内置角色：仅 admin，拥有全部权限且不可改名/删除/削减权限 */
    private static final String ADMIN_ROLE = LocalLoginService.ADMIN_ROLE;
    private static final Pattern ROLE_CODE_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{1,31}$");
    private static final int MAX_ROLE_NAME_LENGTH = 64;
    private static final int MAX_DESCRIPTION_LENGTH = 500;
    private static final Map<String, String> MODULE_NAMES = Map.ofEntries(
            Map.entry("KNOWLEDGE_BASE", "知识库"),
            Map.entry("AGENT", "Agent"),
            Map.entry("WORKFLOW", "工作流"),
            Map.entry("TOOL", "工具"),
            Map.entry("PROMPT", "Prompt 模板"),
            Map.entry("MODEL", "模型"),
            Map.entry("AUDIT", "审计与统计")
    );
    /** 权限描述随种子权限定义（权限表只读，无权限项增删接口） */
    private static final Map<String, String> PERMISSION_DESCRIPTIONS = Map.ofEntries(
            Map.entry("kb:read", "查看/检索知识库"),
            Map.entry("kb:write", "新增/编辑知识库与文件"),
            Map.entry("kb:delete", "删除知识库与文件"),
            Map.entry("agent:use", "使用 Agent 能力"),
            Map.entry("workflow:use", "运行工作流"),
            Map.entry("workflow:edit", "创建/编辑工作流"),
            Map.entry("tool:use", "调用工具与 Skill"),
            Map.entry("prompt:edit", "管理 Prompt 模板"),
            Map.entry("model:manage", "管理大模型/嵌入/重排序模型"),
            Map.entry("audit:view", "查看审计日志与统计")
    );

    private final RoleMapper roleMapper;
    private final PermissionMapper permissionMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final UserRoleMapper userRoleMapper;
    private final QuotaAdminGuard quotaAdminGuard;

    @Operation(summary = "角色列表（含内置标记、用户数、权限数）")
    @GetMapping("/roles")
    public Result<List<Map<String, Object>>> listRoles() {
        quotaAdminGuard.requireAdmin();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (WghRole role : roleMapper.selectAllActive()) {
            rows.add(buildRoleRow(role));
        }
        return Result.success(rows);
    }

    @Operation(summary = "创建角色（role_code 唯一）")
    @PostMapping("/roles")
    @Transactional(rollbackFor = Exception.class)
    public Result<Map<String, Object>> createRole(@RequestBody RoleRequest request) {
        quotaAdminGuard.requireAdmin();

        String roleCode = StringUtils.hasText(request.getRoleCode())
                ? request.getRoleCode().trim() : "";
        if (!ROLE_CODE_PATTERN.matcher(roleCode).matches()) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "role_code 需为 2-32 位，小写字母开头，仅含小写字母、数字、下划线");
        }
        String roleName = validateRoleName(request.getRoleName());
        String description = validateDescription(request.getDescription());
        // 不做前置查重，依赖 wgh_role.role_code 唯一约束；冲突由 GlobalExceptionHandler 映射为 409

        WghRole role = new WghRole();
        role.setRoleCode(roleCode);
        role.setRoleName(roleName);
        role.setDescription(description);
        roleMapper.insertRole(role);

        log.info("创建角色 roleCode={}, roleName={}", roleCode, roleName);
        return Result.success(buildRoleRow(role));
    }

    @Operation(summary = "修改角色名称/描述（内置 admin 不可修改）")
    @PutMapping("/roles/{roleCode}")
    @Transactional(rollbackFor = Exception.class)
    public Result<Map<String, Object>> updateRole(@PathVariable String roleCode,
                                                  @RequestBody RoleRequest request) {
        quotaAdminGuard.requireAdmin();

        WghRole role = requireRole(roleCode);
        if (isBuiltIn(role.getRoleCode())) {
            throw new BusinessException(ResultCode.OPERATION_NOT_ALLOWED, "内置 admin 角色不可修改");
        }
        role.setRoleName(validateRoleName(request.getRoleName()));
        role.setDescription(validateDescription(request.getDescription()));
        roleMapper.updateRoleInfo(role);

        log.info("修改角色 roleCode={}, roleName={}", role.getRoleCode(), role.getRoleName());
        return Result.success(buildRoleRow(role));
    }

    @Operation(summary = "删除角色（内置 admin 不可删除；仍有用户绑定时返回 409）")
    @DeleteMapping("/roles/{roleCode}")
    @Transactional(rollbackFor = Exception.class)
    public ResponseEntity<Result<Void>> deleteRole(@PathVariable String roleCode) {
        quotaAdminGuard.requireAdmin();

        WghRole role = requireRole(roleCode);
        if (isBuiltIn(role.getRoleCode())) {
            throw new BusinessException(ResultCode.OPERATION_NOT_ALLOWED, "内置 admin 角色不可删除");
        }
        long boundUsers = userRoleMapper.countUsersByRoleId(role.getId());
        if (boundUsers > 0) {
            log.warn("删除角色被拒：仍有用户绑定 roleCode={}, users={}", role.getRoleCode(), boundUsers);
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Result.fail(ResultCode.OPERATION_NOT_ALLOWED.getCode(),
                            "角色仍被 " + boundUsers + " 个用户绑定，请先调整这些用户的角色"));
        }

        rolePermissionMapper.deleteByRoleId(role.getId());
        roleMapper.deleteRoleById(role.getId());
        log.info("删除角色 roleCode={}", role.getRoleCode());
        return ResponseEntity.ok(Result.success());
    }

    @Operation(summary = "权限项列表（按模块分组）")
    @GetMapping("/permissions")
    public Result<List<Map<String, Object>>> listPermissions() {
        quotaAdminGuard.requireAdmin();

        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        Map<String, String> moduleLabels = new LinkedHashMap<>();
        for (WghPermission permission : permissionMapper.selectAllOrdered()) {
            String module = StringUtils.hasText(permission.getResourceType())
                    ? permission.getResourceType() : "OTHER";
            moduleLabels.putIfAbsent(module, MODULE_NAMES.getOrDefault(module, module));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("permCode", permission.getPermCode());
            item.put("permName", permission.getPermName());
            item.put("description", PERMISSION_DESCRIPTIONS.getOrDefault(
                    permission.getPermCode(), permission.getPermName()));
            grouped.computeIfAbsent(module, key -> new ArrayList<>()).add(item);
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> entry : grouped.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("module", entry.getKey());
            row.put("moduleName", moduleLabels.get(entry.getKey()));
            row.put("permissions", entry.getValue());
            rows.add(row);
        }
        return Result.success(rows);
    }

    @Operation(summary = "整体覆盖角色权限（幂等；内置 admin 只允许提交全量权限）")
    @PutMapping("/roles/{roleCode}/permissions")
    @Transactional(rollbackFor = Exception.class)
    public Result<Map<String, Object>> updateRolePermissions(@PathVariable String roleCode,
                                                             @RequestBody RolePermissionRequest request) {
        quotaAdminGuard.requireAdmin();

        WghRole role = requireRole(roleCode);
        Set<String> requested = new LinkedHashSet<>();
        if (request.getPermCodes() != null) {
            for (String code : request.getPermCodes()) {
                if (StringUtils.hasText(code)) {
                    requested.add(code.trim());
                }
            }
        }

        // 权限码必须来自已知集合，未知码直接拒绝而不是静默忽略
        List<WghPermission> found = requested.isEmpty()
                ? List.of() : permissionMapper.selectByCodes(requested);
        Set<String> knownCodes = new LinkedHashSet<>();
        for (WghPermission permission : found) {
            knownCodes.add(permission.getPermCode());
        }
        List<String> unknown = requested.stream().filter(code -> !knownCodes.contains(code)).toList();
        if (!unknown.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "未知权限码: " + String.join(", ", unknown));
        }

        if (isBuiltIn(role.getRoleCode())) {
            Set<String> allCodes = allPermissionCodes();
            if (!requested.equals(allCodes)) {
                throw new BusinessException(ResultCode.OPERATION_NOT_ALLOWED,
                        "内置 admin 角色必须保留全部权限，不允许削减");
            }
            // 已是全量权限：幂等返回，不做任何写入
            return Result.success(buildRoleRow(role));
        }

        rolePermissionMapper.deleteByRoleId(role.getId());
        for (WghPermission permission : found) {
            rolePermissionMapper.insert(role.getId(), permission.getId());
        }
        log.info("更新角色权限 roleCode={}, permissionCount={}", role.getRoleCode(), found.size());
        return Result.success(buildRoleRow(role));
    }

    // ==================== 内部辅助 ====================

    private WghRole requireRole(String roleCode) {
        WghRole role = roleMapper.selectActiveByCode(roleCode == null ? "" : roleCode.trim());
        if (role == null) {
            throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "角色不存在: " + roleCode);
        }
        return role;
    }

    private String validateRoleName(String roleName) {
        String name = roleName == null ? "" : roleName.trim();
        if (name.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "角色名称不能为空");
        }
        if (name.length() > MAX_ROLE_NAME_LENGTH) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "角色名称不能超过 " + MAX_ROLE_NAME_LENGTH + " 个字符");
        }
        return name;
    }

    private String validateDescription(String description) {
        String value = description == null ? "" : description.trim();
        if (value.length() > MAX_DESCRIPTION_LENGTH) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "角色描述不能超过 " + MAX_DESCRIPTION_LENGTH + " 个字符");
        }
        return value;
    }

    private boolean isBuiltIn(String roleCode) {
        return ADMIN_ROLE.equalsIgnoreCase(roleCode);
    }

    private Set<String> allPermissionCodes() {
        Set<String> codes = new LinkedHashSet<>();
        for (WghPermission permission : permissionMapper.selectAllOrdered()) {
            codes.add(permission.getPermCode());
        }
        return codes;
    }

    private Map<String, Object> buildRoleRow(WghRole role) {
        List<String> permCodes = rolePermissionMapper.selectPermCodesByRoleId(role.getId());
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("roleCode", role.getRoleCode());
        row.put("roleName", role.getRoleName());
        row.put("description", role.getDescription());
        row.put("builtIn", isBuiltIn(role.getRoleCode()));
        row.put("userCount", userRoleMapper.countUsersByRoleId(role.getId()));
        row.put("permissionCount", permCodes == null ? 0 : permCodes.size());
        row.put("permCodes", permCodes == null ? List.of() : permCodes);
        return row;
    }
}
