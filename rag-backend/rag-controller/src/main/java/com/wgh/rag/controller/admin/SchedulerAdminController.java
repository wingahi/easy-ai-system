package com.wgh.rag.controller.admin;

import com.wgh.rag.auth.constant.AuthConstants;
import com.wgh.rag.common.result.PageResult;
import com.wgh.rag.common.result.Result;
import com.wgh.rag.common.security.RequiresPermission;
import com.wgh.rag.log.annotation.Audit;
import com.wgh.rag.scheduler.admin.SchedulerAdminService;
import com.wgh.rag.scheduler.admin.SchedulerCancelResult;
import com.wgh.rag.scheduler.admin.SchedulerJobDetail;
import com.wgh.rag.scheduler.admin.SchedulerJobSummary;
import com.wgh.rag.scheduler.admin.SchedulerInstanceView;
import com.wgh.rag.scheduler.admin.SchedulerOverview;
import com.wgh.rag.scheduler.reconcile.ReconcileReport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 调度中心管理端接口：作业列表/详情/取消与概览统计。
 *
 * <p>只读查询以作业表为准；取消委托调度层统一执行门面，不在此处改写状态机。
 */
@Tag(name = "调度中心")
@RestController
@RequestMapping("/api/admin/scheduler")
@RequiredArgsConstructor
@RequiresPermission(AuthConstants.PERM_AUDIT_VIEW)
public class SchedulerAdminController {

    private final SchedulerAdminService schedulerAdminService;

    @Operation(summary = "调度概览（状态/车道分布与关键计数）")
    @GetMapping("/overview")
    public Result<SchedulerOverview> overview() {
        return Result.success(schedulerAdminService.overview());
    }

    @Operation(summary = "实例视图（存活/心跳/车道快照；失联实例标灰依据）")
    @GetMapping("/instances")
    public Result<List<SchedulerInstanceView>> instances() {
        return Result.success(schedulerAdminService.instances());
    }

    @Operation(summary = "最近一次作业/业务对账报告（本实例）")
    @GetMapping("/reconcile")
    public Result<ReconcileReport> reconcile() {
        return Result.success(schedulerAdminService.reconcileReport());
    }

    @Operation(summary = "分页查询后台作业")
    @GetMapping("/jobs")
    public Result<PageResult<SchedulerJobSummary>> jobs(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String lane,
            @RequestParam(required = false) String jobType,
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String bizKey,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return Result.success(schedulerAdminService.page(
                status, lane, jobType, tenantId, bizKey, from, to, page, size));
    }

    @Operation(summary = "作业详情（负载/结果/租约/取消与重试信息）")
    @GetMapping("/jobs/{executionId}")
    public Result<SchedulerJobDetail> detail(@PathVariable String executionId) {
        return Result.success(schedulerAdminService.detail(executionId));
    }

    @Operation(summary = "取消作业（不可取消状态返回明确业务错误）")
    @PostMapping("/jobs/{executionId}/cancel")
    @Audit(action = "SCHEDULER_JOB_CANCEL", resourceType = "SCHEDULER")
    public Result<SchedulerCancelResult> cancel(@PathVariable String executionId,
                                                @RequestBody(required = false) Map<String, String> body) {
        String reason = body == null ? null : body.get("reason");
        return Result.success(schedulerAdminService.cancel(executionId, reason));
    }
}
