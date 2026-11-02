package com.wgh.rag.publicaccess.controller;

import com.wgh.rag.common.annotation.Audit;
import com.wgh.rag.common.result.PageResult;
import com.wgh.rag.common.result.Result;
import com.wgh.rag.common.security.SecurityUtils;
import com.wgh.rag.publicaccess.entity.ShareLink;
import com.wgh.rag.publicaccess.service.ShareLinkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 管理端：免登录分享链接
 */
@Tag(name = "分享链接管理")
@RestController
@RequestMapping("/api/admin/share-links")
@RequiredArgsConstructor
public class ShareLinkAdminController {

    private final ShareLinkService shareLinkService;

    @Operation(summary = "分页查询分享链接")
    @GetMapping
    public Result<PageResult<ShareLink>> page(
            @RequestParam(defaultValue = "1") Long page,
            @RequestParam(defaultValue = "10") Long size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String scope) {
        return Result.success(shareLinkService.page(page, size, keyword, scope));
    }

    @Operation(summary = "创建分享链接（支持有效期 / 访问密码 / 只读范围 / 次数限制）")
    @PostMapping
    @Audit(action = "SHARE_LINK_CREATE", resourceType = "SHARE_LINK")
    public Result<ShareLink> create(@RequestBody Map<String, Object> body) {
        ShareLink req = new ShareLink();
        req.setName(body.get("name") == null ? null : String.valueOf(body.get("name")));
        req.setScope(body.get("scope") == null ? null : String.valueOf(body.get("scope")));
        req.setTargetId(body.get("targetId") == null ? null : Long.parseLong(String.valueOf(body.get("targetId"))));
        // 前端传密码明文（字段名沿用实体列名 passwordHash，落库前由 Service 做 BCrypt）
        req.setPasswordHash(body.get("password") == null ? null : String.valueOf(body.get("password")));
        req.setMaxUses(body.get("maxUses") == null ? null : Integer.parseInt(String.valueOf(body.get("maxUses"))));
        if (body.get("expiresDays") != null) {
            int days = Integer.parseInt(String.valueOf(body.get("expiresDays")));
            if (days > 0) req.setExpiresAt(LocalDateTime.now().plusDays(days));
        }
        return Result.success(shareLinkService.create(req, SecurityUtils.currentUsername()));
    }

    @Operation(summary = "吊销分享链接（立即失效）")
    @PostMapping("/{id}/revoke")
    @Audit(action = "SHARE_LINK_REVOKE", resourceType = "SHARE_LINK")
    public Result<Void> revoke(@PathVariable Long id) {
        shareLinkService.revoke(id, SecurityUtils.currentUsername());
        return Result.success();
    }
}
