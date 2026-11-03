package com.wgh.rag.controller;

import com.wgh.rag.chat.mapper.ChatMessageMapper;
import com.wgh.rag.chat.service.FeedbackService;
import com.wgh.rag.log.entity.AuditLog;
import com.wgh.rag.quota.entity.QuotaUsage;
import com.wgh.rag.quota.service.QuotaService;
import com.wgh.rag.common.result.PageResult;
import com.wgh.rag.common.result.Result;
import com.wgh.rag.common.security.RequiresPermission;
import com.wgh.rag.common.security.SecurityUtils;
import com.wgh.rag.log.service.AuditLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理端统计：审计日志 / 成本统计 / 配额用量
 */
@Tag(name = "管理端统计")
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@RequiresPermission("audit:view")
public class AdminStatsController {

    private final AuditLogService auditLogService;
    private final QuotaService quotaService;
    private final ChatMessageMapper chatMessageMapper;
    private final FeedbackService feedbackService;

    @Operation(summary = "分页查询审计日志")
    @GetMapping("/audit-logs")
    public Result<PageResult<AuditLog>> auditLogs(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String action) {
        return Result.success(auditLogService.page(page, size, username, action));
    }

    @Operation(summary = "成本统计（按天 + 模型聚合，默认近 7 天）")
    @GetMapping("/stats/cost")
    public Result<List<Map<String, Object>>> costStats(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        String toDate = (to != null && !to.isBlank()) ? to : LocalDate.now().format(fmt);
        String fromDate = (from != null && !from.isBlank()) ? from : LocalDate.now().minusDays(6).format(fmt);
        return Result.success(chatMessageMapper.costStatsByDayAndModel(fromDate, toDate));
    }

    @Operation(summary = "当前用户当日配额用量与上限")
    @GetMapping("/stats/quota")
    public Result<Map<String, Object>> quotaStats() {
        String username = SecurityUtils.currentUsername();
        QuotaUsage usage = quotaService.todayUsage(username);
        Map<String, Object> data = new HashMap<>();
        data.put("username", username);
        data.put("dateKey", quotaService.todayKey());
        data.put("chatCount", usage != null && usage.getChatCount() != null ? usage.getChatCount() : 0);
        data.put("tokenUsed", usage != null && usage.getTokenUsed() != null ? usage.getTokenUsed() : 0L);
        data.put("chatDailyLimit", quotaService.getChatDailyLimit());
        data.put("tokenDailyLimit", quotaService.getTokenDailyLimit());
        return Result.success(data);
    }

    @Operation(summary = "差评反馈分页（运营闭环：差评答案 + 触发问题 + 窗口）")
    @GetMapping("/feedback/dislikes")
    public Result<PageResult<Map<String, Object>>> dislikes(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        return Result.success(feedbackService.dislikePage(page, size));
    }
}
