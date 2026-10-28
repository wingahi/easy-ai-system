package com.wgh.rag.controller;

import com.wgh.rag.common.result.Result;
import com.wgh.rag.workflow.entity.WorkflowRun;
import com.wgh.rag.workflow.entity.WorkflowTrigger;
import com.wgh.rag.workflow.service.WorkflowTriggerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 工作流触发器 API —— CRON/Webhook 触发器 CRUD + Webhook 免登录触发入口。
 */
@Tag(name = "工作流触发器")
@RestController
@RequiredArgsConstructor
public class WorkflowTriggerController {

    private final WorkflowTriggerService triggerService;

    @Operation(summary = "工作流的触发器列表")
    @GetMapping("/api/workflows/{id}/triggers")
    public Result<List<WorkflowTrigger>> list(@PathVariable Long id) {
        return Result.success(triggerService.listByWorkflow(id));
    }

    @Operation(summary = "新建触发器（CRON / WEBHOOK）")
    @PostMapping("/api/workflows/{id}/triggers")
    public Result<WorkflowTrigger> create(@PathVariable Long id, @RequestBody WorkflowTrigger payload) {
        return Result.success(triggerService.create(id, payload));
    }

    @Operation(summary = "更新触发器")
    @PutMapping("/api/workflows/triggers/{triggerId}")
    public Result<WorkflowTrigger> update(@PathVariable Long triggerId, @RequestBody WorkflowTrigger payload) {
        return Result.success(triggerService.update(triggerId, payload));
    }

    @Operation(summary = "启用触发器")
    @PostMapping("/api/workflows/triggers/{triggerId}/enable")
    public Result<Void> enable(@PathVariable Long triggerId) {
        triggerService.setStatus(triggerId, 1);
        return Result.success();
    }

    @Operation(summary = "停用触发器")
    @PostMapping("/api/workflows/triggers/{triggerId}/disable")
    public Result<Void> disable(@PathVariable Long triggerId) {
        triggerService.setStatus(triggerId, 0);
        return Result.success();
    }

    @Operation(summary = "删除触发器")
    @DeleteMapping("/api/workflows/triggers/{triggerId}")
    public Result<Void> delete(@PathVariable Long triggerId) {
        triggerService.delete(triggerId);
        return Result.success();
    }

    @Operation(summary = "Webhook 触发入口（凭 token 免登录）")
    @PostMapping("/api/workflows/webhook/{token}")
    public Result<Map<String, Object>> webhook(@PathVariable String token,
                                               @RequestBody(required = false) Map<String, Object> body) {
        String input = body != null && body.get("input") != null ? String.valueOf(body.get("input")) : null;
        WorkflowRun run = triggerService.fireWebhook(token, input);
        return Result.success(Map.of("runId", run.getId(), "status", run.getStatus()));
    }
}
