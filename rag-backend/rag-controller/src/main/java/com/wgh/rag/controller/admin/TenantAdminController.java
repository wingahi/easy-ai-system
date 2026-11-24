package com.wgh.rag.controller.admin;

import com.wgh.rag.auth.constant.AuthConstants;
import com.wgh.rag.auth.entity.WghTenant;
import com.wgh.rag.auth.tenant.TenantService;
import com.wgh.rag.common.result.Result;
import com.wgh.rag.common.security.RequiresPermission;
import com.wgh.rag.log.annotation.Audit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 租户管理（平台侧）。
 * <p>阶段 1 只提供目录维护；租户管理员与平台管理员的能力边界见多租户路线图待拍板项。</p>
 */
@Tag(name = "租户管理")
@RestController
@RequestMapping("/api/admin/tenants")
@RequiredArgsConstructor
public class TenantAdminController {

    private final TenantService tenantService;

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

    @Operation(summary = "启用/停用租户")
    @PatchMapping("/{id}/status")
    @RequiresPermission(AuthConstants.PERM_TENANT_MANAGE)
    @Audit(action = "TENANT_STATUS_UPDATE", resourceType = "TENANT")
    public Result<WghTenant> updateStatus(@PathVariable Long id, @RequestParam Integer status) {
        return Result.success(tenantService.updateStatus(id, status));
    }
}
