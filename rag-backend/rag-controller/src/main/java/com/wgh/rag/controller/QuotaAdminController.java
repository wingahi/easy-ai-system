package com.wgh.rag.controller;

import com.wgh.rag.common.result.PageResult;
import com.wgh.rag.common.result.Result;
import com.wgh.rag.quota.entity.QuotaUsage;
import com.wgh.rag.quota.entity.UserQuotaConfig;
import com.wgh.rag.quota.service.EffectiveQuota;
import com.wgh.rag.quota.service.QuotaAdminGuard;
import com.wgh.rag.quota.service.QuotaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 配额管理端接口（仅管理员）：按用户查询/配置/重置配额。
 */
@Tag(name = "配额管理")
@RestController
@RequestMapping("/api/admin/quota")
@RequiredArgsConstructor
public class QuotaAdminController {

    private final QuotaService quotaService;
    private final QuotaAdminGuard quotaAdminGuard;

    @Operation(summary = "分页列出用户配额（自定义配置 ∪ 有用量记录的用户）")
    @GetMapping("/users")
    public Result<PageResult<Map<String, Object>>> listUsers(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String username) {
        quotaAdminGuard.requireAdmin();

        Map<String, UserQuotaConfig> configMap = new HashMap<>();
        for (UserQuotaConfig cfg : quotaService.listAllUserConfigs()) {
            configMap.put(cfg.getUsername(), cfg);
        }
        Set<String> usernames = new LinkedHashSet<>(configMap.keySet());
        usernames.addAll(quotaService.listUsernamesWithUsage());
        if (username != null && !username.isBlank()) {
            String kw = username.trim();
            usernames.removeIf(u -> !u.contains(kw));
        }

        List<String> sorted = new ArrayList<>(usernames);
        sorted.sort(Comparator.naturalOrder());

        long total = sorted.size();
        int from = (int) Math.min(Math.max(0, (page - 1) * size), total);
        int to = (int) Math.min(from + size, total);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (String u : sorted.subList(from, to)) {
            rows.add(buildRow(u, configMap.get(u)));
        }
        return Result.success(new PageResult<>(total, page, size, rows));
    }

    @Operation(summary = "查询某用户配额（生效配置 + 当期用量）")
    @GetMapping("/users/{username}")
    public Result<Map<String, Object>> getUser(@PathVariable String username) {
        quotaAdminGuard.requireAdmin();
        return Result.success(buildRow(username, quotaService.findUserConfig(username)));
    }

    @Operation(summary = "修改/创建某用户配额配置")
    @PutMapping("/users/{username}")
    public Result<Map<String, Object>> saveUser(@PathVariable String username,
                                                @RequestBody UserQuotaConfig body) {
        quotaAdminGuard.requireAdmin();
        body.setId(null);
        body.setUsername(username);
        quotaService.saveUserConfig(body);
        return Result.success(buildRow(username, quotaService.findUserConfig(username)));
    }

    @Operation(summary = "重置某用户当期用量（清零计数，不修改配置）")
    @PostMapping("/users/{username}/reset-usage")
    public Result<Map<String, Object>> resetUsage(@PathVariable String username) {
        quotaAdminGuard.requireAdmin();
        boolean reset = quotaService.resetUsage(username);
        Map<String, Object> row = buildRow(username, quotaService.findUserConfig(username));
        row.put("resetUsage", reset);
        return Result.success(row);
    }

    @Operation(summary = "恢复某用户配额为全局默认（逻辑删除自定义配置）")
    @DeleteMapping("/users/{username}")
    public Result<Map<String, Object>> resetUser(@PathVariable String username) {
        quotaAdminGuard.requireAdmin();
        boolean removed = quotaService.resetUserConfig(username);
        Map<String, Object> row = buildRow(username, null);
        row.put("restoredDefault", removed);
        return Result.success(row);
    }

    /**
     * 列表行：生效配置（自定义优先，否则默认）+ 当期用量。
     * roles 字段预留：RBAC 落地前恒为空数组，前端展示 "—"。
     */
    private Map<String, Object> buildRow(String username, UserQuotaConfig cfg) {
        EffectiveQuota effective = quotaService.resolve(username);
        String periodKey = quotaService.currentPeriodKey(effective.getPeriod());
        QuotaUsage usage = quotaService.usageOf(username, periodKey);

        Map<String, Object> row = new HashMap<>();
        row.put("username", username);
        row.put("roles", List.of());
        row.put("custom", EffectiveQuota.SOURCE_CUSTOM.equals(effective.getSource()));
        row.put("period", effective.getPeriod());
        row.put("periodKey", periodKey);
        row.put("chatLimit", effective.getChatLimit());
        row.put("tokenLimit", effective.getTokenLimit());
        row.put("enabled", cfg == null || Boolean.TRUE.equals(cfg.getEnabled()));
        row.put("remark", cfg != null ? cfg.getRemark() : null);
        row.put("configUpdateTime", cfg != null ? cfg.getUpdateTime() : null);
        row.put("chatUsed", usage != null && usage.getChatCount() != null ? usage.getChatCount() : 0);
        row.put("tokenUsed", usage != null && usage.getTokenUsed() != null ? usage.getTokenUsed() : 0L);
        return row;
    }
}
