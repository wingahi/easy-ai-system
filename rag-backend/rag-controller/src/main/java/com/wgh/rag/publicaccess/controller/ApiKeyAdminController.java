package com.wgh.rag.publicaccess.controller;

import com.wgh.rag.common.annotation.Audit;
import com.wgh.rag.common.result.PageResult;
import com.wgh.rag.common.result.Result;
import com.wgh.rag.common.security.SecurityUtils;
import com.wgh.rag.publicaccess.entity.ApiCallLog;
import com.wgh.rag.publicaccess.entity.ApiKey;
import com.wgh.rag.publicaccess.service.ApiCallLogService;
import com.wgh.rag.publicaccess.service.ApiKeyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理端：开放 API Key
 */
@Tag(name = "开放 API Key 管理")
@RestController
@RequestMapping("/api/admin/api-keys")
@RequiredArgsConstructor
public class ApiKeyAdminController {

    private final ApiKeyService apiKeyService;
    private final ApiCallLogService apiCallLogService;

    @Operation(summary = "分页查询 API Key（不返回明文与哈希）")
    @GetMapping
    public Result<PageResult<ApiKey>> page(
            @RequestParam(defaultValue = "1") Long page,
            @RequestParam(defaultValue = "10") Long size,
            @RequestParam(required = false) String keyword) {
        return Result.success(apiKeyService.page(page, size, keyword));
    }

    @Operation(summary = "创建 API Key（明文仅本次返回）")
    @PostMapping
    @Audit(action = "API_KEY_CREATE", resourceType = "API_KEY")
    public Result<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        String name = body.get("name") == null ? null : String.valueOf(body.get("name"));
        List<String> scopes = new ArrayList<>();
        Object rawScopes = body.get("scopes");
        if (rawScopes instanceof List<?> list) {
            list.forEach(s -> scopes.add(String.valueOf(s)));
        } else if (rawScopes != null && !String.valueOf(rawScopes).isBlank()) {
            for (String s : String.valueOf(rawScopes).split(",")) scopes.add(s.trim());
        }
        LocalDateTime expiresAt = null;
        if (body.get("expiresDays") != null) {
            int days = Integer.parseInt(String.valueOf(body.get("expiresDays")));
            if (days > 0) expiresAt = LocalDateTime.now().plusDays(days);
        }
        ApiKeyService.CreatedKey created = apiKeyService.create(
                name, scopes, expiresAt, rawList(body.get("ipWhitelist")), SecurityUtils.currentUsername());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", created.record().getId());
        data.put("name", created.record().getName());
        data.put("scopes", created.record().getScopes());
        data.put("ipWhitelist", created.record().getIpWhitelist());
        data.put("plainKey", created.plainKey());
        data.put("expiresAt", created.record().getExpiresAt());
        return Result.success(data);
    }

    @Operation(summary = "设置 / 更新 API Key IP 白名单（空表示不限制）")
    @PutMapping("/{id}/ip-whitelist")
    @Audit(action = "API_KEY_UPDATE_IP_WHITELIST", resourceType = "API_KEY")
    public Result<ApiKey> updateIpWhitelist(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return Result.success(apiKeyService.updateIpWhitelist(
                id, rawList(body.get("ipWhitelist")), SecurityUtils.currentUsername()));
    }

    @Operation(summary = "吊销 API Key")
    @PostMapping("/{id}/revoke")
    @Audit(action = "API_KEY_REVOKE", resourceType = "API_KEY")
    public Result<Void> revoke(@PathVariable Long id) {
        apiKeyService.revoke(id, SecurityUtils.currentUsername());
        return Result.success();
    }

    @Operation(summary = "某 API Key 的最近调用日志")
    @GetMapping("/{id}/call-logs")
    public Result<List<ApiCallLog>> callLogs(@PathVariable Long id,
                                             @RequestParam(defaultValue = "20") Integer limit) {
        return Result.success(apiCallLogService.recent(id, limit));
    }

    private String rawList(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof List<?> list) {
            return list.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse(null);
        }
        return String.valueOf(raw);
    }
}
