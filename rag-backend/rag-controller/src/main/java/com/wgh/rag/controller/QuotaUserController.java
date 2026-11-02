package com.wgh.rag.controller;

import com.wgh.rag.common.result.Result;
import com.wgh.rag.quota.entity.QuotaUsage;
import com.wgh.rag.quota.service.EffectiveQuota;
import com.wgh.rag.quota.service.QuotaService;
import com.wgh.rag.quota.service.QuotaUserResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 用户侧配额接口：查看自己的配额与当期已用量。
 */
@Tag(name = "我的配额")
@RestController
@RequestMapping("/api/quota")
@RequiredArgsConstructor
public class QuotaUserController {

    private final QuotaService quotaService;
    private final QuotaUserResolver quotaUserResolver;

    @Operation(summary = "当前用户生效配额与当期用量")
    @GetMapping("/my")
    public Result<Map<String, Object>> my() {
        String username = quotaUserResolver.currentUsername();
        EffectiveQuota quota = quotaService.resolve(username);
        String periodKey = quotaService.currentPeriodKey(quota.getPeriod());
        QuotaUsage usage = quotaService.usageOf(username, periodKey);

        long chatUsed = usage != null && usage.getChatCount() != null ? usage.getChatCount() : 0L;
        long tokenUsed = usage != null && usage.getTokenUsed() != null ? usage.getTokenUsed() : 0L;

        Map<String, Object> data = new HashMap<>();
        data.put("username", username);
        data.put("source", quota.getSource());
        data.put("period", quota.getPeriod());
        data.put("periodKey", periodKey);
        data.put("chatLimit", quota.getChatLimit());
        data.put("chatUsed", chatUsed);
        data.put("tokenLimit", quota.getTokenLimit());
        data.put("tokenUsed", tokenUsed);
        data.put("chatRemaining", remaining(quota.getChatLimit(), chatUsed));
        data.put("tokenRemaining", remaining(quota.getTokenLimit(), tokenUsed));
        data.put("nearLimit", nearLimit(quota.getChatLimit(), chatUsed) || nearLimit(quota.getTokenLimit(), tokenUsed));
        return Result.success(data);
    }

    /** 剩余量；上限 <=0 表示不限制，返回 null */
    private Long remaining(Number limit, long used) {
        if (limit == null || limit.longValue() <= 0) return null;
        return Math.max(0, limit.longValue() - used);
    }

    /** 用量达到上限 90% 视为接近上限 */
    private boolean nearLimit(Number limit, long used) {
        return limit != null && limit.longValue() > 0 && used * 10 >= limit.longValue() * 9;
    }
}
