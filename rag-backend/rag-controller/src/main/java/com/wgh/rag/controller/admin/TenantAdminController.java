package com.wgh.rag.controller.admin;

import com.wgh.rag.auth.constant.AuthConstants;
import com.wgh.rag.auth.entity.WghTenant;
import com.wgh.rag.auth.tenant.TenantService;
import com.wgh.rag.common.result.Result;
import com.wgh.rag.common.security.RequiresPermission;
import com.wgh.rag.log.annotation.Audit;
import com.wgh.rag.quota.service.TenantQuotaConfigService;
import com.wgh.rag.quota.service.TenantQuotaView;
import com.wgh.rag.scheduler.admin.SchedulerAdminService;
import com.wgh.rag.scheduler.admin.SchedulerPolicyUpdate;
import com.wgh.rag.scheduler.admin.SchedulerPolicyView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 租户管理（平台侧）。
 *
 * <p>能力边界：列表/创建沿用 {@code tenant:manage}；更新、下线、配额、调度策略分别使用
 * {@code tenant:update} / {@code tenant:disable} / {@code tenant:quota} / {@code tenant:schedule}，
 * 写操作全部落操作审计。下线只做逻辑删除，不删除任何业务数据。
 */
@Tag(name = "租户管理")
@RestController
@RequestMapping("/api/admin/tenants")
@RequiredArgsConstructor
public class TenantAdminController {

    private final TenantService tenantService;
    private final TenantQuotaConfigService tenantQuotaConfigService;
    private final SchedulerAdminService schedulerAdminService;

    @Operation(summary = "租户列表")
    @GetMapping
    @RequiresPermission(AuthConstants.PERM_TENANT_MANAGE)
    public Result<List<WghTenant>> list() {
        return Result.success(tenantService.list());
    }

    @Operation(summary = "创建租户")
    @PostMapping
    @RequiresPermission(AuthConstants.PERM_TENANT_MANAGE)
    @Audit(action = "TENANT_CREATE", resourceType = "TENANT")
    public Result<WghTenant> create(@RequestBody Map<String, Object> body) {
        String code = body.get("code") == null ? null : String.valueOf(body.get("code"));
        String name = body.get("name") == null ? null : String.valueOf(body.get("name"));
        String plan = body.get("plan") == null ? null : String.valueOf(body.get("plan"));
        return Result.success(tenantService.create(code, name, plan));
    }

    @Operation(summary = "更新租户基本信息")
    @PutMapping("/{id}")
    @RequiresPermission(AuthConstants.PERM_TENANT_UPDATE)
    @Audit(action = "TENANT_UPDATE", resourceType = "TENANT")
    public Result<WghTenant> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        String name = stringValue(body.get("name"));
        String plan = stringValue(body.get("plan"));
        String isolationMode = stringValue(body.get("isolationMode"));
        return Result.success(tenantService.update(id, name, plan, isolationMode));
    }

    @Operation(summary = "启用/停用租户")
    @PatchMapping("/{id}/status")
    @RequiresPermission(AuthConstants.PERM_TENANT_MANAGE)
    @Audit(action = "TENANT_STATUS_UPDATE", resourceType = "TENANT")
    public Result<WghTenant> updateStatus(@PathVariable Long id, @RequestParam Integer status) {
        return Result.success(tenantService.updateStatus(id, status));
    }

    @Operation(summary = "租户下线（逻辑删除，不删除业务数据）")
    @DeleteMapping("/{id}")
    @RequiresPermission(AuthConstants.PERM_TENANT_DISABLE)
    @Audit(action = "TENANT_OFFLINE", resourceType = "TENANT")
    public Result<Void> offline(@PathVariable Long id) {
        tenantService.offline(id);
        return Result.success(null);
    }

    @Operation(summary = "读取租户配额配置")
    @GetMapping("/{id}/quota")
    @RequiresPermission(AuthConstants.PERM_TENANT_QUOTA)
    public Result<TenantQuotaView> quota(@PathVariable Long id) {
        return Result.success(tenantQuotaConfigService.get(tenantService.requireById(id).getCode()));
    }

    @Operation(summary = "保存租户配额配置")
    @PutMapping("/{id}/quota")
    @RequiresPermission(AuthConstants.PERM_TENANT_QUOTA)
    @Audit(action = "TENANT_QUOTA_UPDATE", resourceType = "TENANT")
    public Result<TenantQuotaView> saveQuota(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        String tenantCode = tenantService.requireById(id).getCode();
        return Result.success(tenantQuotaConfigService.save(
                tenantCode,
                stringValue(body.get("period")),
                intValue(body.get("chatLimit")),
                longValue(body.get("tokenLimit")),
                boolValue(body.get("enabled")),
                stringValue(body.get("remark"))));
    }

    @Operation(summary = "读取租户调度策略")
    @GetMapping("/{id}/schedule-policy")
    @RequiresPermission(AuthConstants.PERM_TENANT_SCHEDULE)
    public Result<List<SchedulerPolicyView>> schedulePolicy(@PathVariable Long id) {
        return Result.success(schedulerAdminService.policies(tenantService.requireById(id).getCode()));
    }

    @Operation(summary = "保存租户调度策略")
    @PutMapping("/{id}/schedule-policy")
    @RequiresPermission(AuthConstants.PERM_TENANT_SCHEDULE)
    @Audit(action = "TENANT_SCHEDULE_UPDATE", resourceType = "TENANT")
    public Result<List<SchedulerPolicyView>> updateSchedulePolicy(
            @PathVariable Long id, @RequestBody List<SchedulerPolicyUpdate> updates) {
        String tenantCode = tenantService.requireById(id).getCode();
        return Result.success(schedulerAdminService.updatePolicies(tenantCode, updates));
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Integer intValue(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof Number number ? number.intValue()
                : Integer.valueOf(String.valueOf(value).trim());
    }

    private static Long longValue(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof Number number ? number.longValue()
                : Long.valueOf(String.valueOf(value).trim());
    }

    private static Boolean boolValue(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof Boolean bool ? bool : Boolean.valueOf(String.valueOf(value).trim());
    }
}
