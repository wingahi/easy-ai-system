package com.wgh.rag.controller;

import com.wgh.rag.common.result.PageResult;
import com.wgh.rag.common.result.Result;
import com.wgh.rag.workflow.entity.Workflow;
import com.wgh.rag.workflow.entity.WorkflowRun;
import com.wgh.rag.workflow.entity.WorkflowVersion;
import com.wgh.rag.workflow.service.WorkflowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * 工作流编排 API —— CRUD + SSE 运行 + HITL 确认 + 运行历史。
 */
@Tag(name = "工作流编排")
@RestController
@RequestMapping("/api/workflows")
@RequiredArgsConstructor
public class WorkflowController {

    private final WorkflowService workflowService;

    // ==================== 定义 CRUD ====================

    @Operation(summary = "分页查询工作流")
    @GetMapping
    public Result<PageResult<Workflow>> page(
            @RequestParam(defaultValue = "1") Long page,
            @RequestParam(defaultValue = "10") Long size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status) {
        return Result.success(workflowService.page(page, size, keyword, status));
    }

    @Operation(summary = "启用中的工作流列表")
    @GetMapping("/list")
    public Result<List<Workflow>> list() {
        return Result.success(workflowService.listEnabled());
    }

    @Operation(summary = "获取工作流详情（含图定义）")
    @GetMapping("/{id}")
    public Result<Workflow> get(@PathVariable Long id) {
        return Result.success(workflowService.getById(id));
    }

    @Operation(summary = "新建工作流")
    @PostMapping
    public Result<Workflow> create(@RequestBody Workflow workflow) {
        return Result.success(workflowService.create(workflow));
    }

    @Operation(summary = "保存工作流（版本号 +1）")
    @PutMapping("/{id}")
    public Result<Workflow> update(@PathVariable Long id, @RequestBody Workflow workflow) {
        return Result.success(workflowService.update(id, workflow));
    }

    @Operation(summary = "校验工作流图")
    @PostMapping("/{id}/validate")
    public Result<List<String>> validate(@PathVariable Long id) {
        return Result.success(workflowService.validateGraph(id));
    }

    @Operation(summary = "删除工作流（逻辑删除）")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        workflowService.delete(id);
        return Result.success();
    }

    @Operation(summary = "启用工作流")
    @PostMapping("/{id}/enable")
    public Result<Void> enable(@PathVariable Long id) {
        workflowService.setStatus(id, 1);
        return Result.success();
    }

    @Operation(summary = "停用工作流")
    @PostMapping("/{id}/disable")
    public Result<Void> disable(@PathVariable Long id) {
        workflowService.setStatus(id, 0);
        return Result.success();
    }

    // ==================== 版本管理 ====================

    @Operation(summary = "版本历史列表")
    @GetMapping("/{id}/versions")
    public Result<List<WorkflowVersion>> versions(@PathVariable Long id) {
        return Result.success(workflowService.listVersions(id));
    }

    @Operation(summary = "版本详情")
    @GetMapping("/{id}/versions/{version}")
    public Result<WorkflowVersion> versionDetail(@PathVariable Long id, @PathVariable Integer version) {
        return Result.success(workflowService.getVersion(id, version));
    }

    @Operation(summary = "回滚到指定版本（生成新版本快照）")
    @PostMapping("/{id}/rollback/{version}")
    public Result<Workflow> rollback(@PathVariable Long id, @PathVariable Integer version) {
        return Result.success(workflowService.rollback(id, version));
    }

    // ==================== 运行 ====================

    @Operation(summary = "SSE 运行工作流")
    @PostMapping(value = "/{id}/run", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter run(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        String input = body != null && body.get("input") != null ? String.valueOf(body.get("input")) : "";
        return workflowService.run(id, input);
    }

    @Operation(summary = "查询运行状态（刷新恢复）")
    @GetMapping("/runs/{runId}/state")
    public Result<Map<String, Object>> runState(@PathVariable Long runId) {
        return Result.success(workflowService.getRunState(runId));
    }

    @Operation(summary = "HITL 人工确认并续跑（SSE）")
    @PostMapping(value = "/runs/{runId}/hitl-confirm", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter hitlConfirm(@PathVariable Long runId,
                                  @RequestBody(required = false) Map<String, Object> body) {
        String userInput = body != null && body.get("userInput") != null
                ? String.valueOf(body.get("userInput")) : "";
        return workflowService.hitlConfirm(runId, userInput);
    }

    @Operation(summary = "取消运行")
    @PostMapping("/runs/{runId}/cancel")
    public Result<Void> cancel(@PathVariable Long runId) {
        workflowService.cancel(runId);
        return Result.success();
    }

    @Operation(summary = "运行历史分页")
    @GetMapping("/{id}/runs")
    public Result<PageResult<WorkflowRun>> pageRuns(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") Long page,
            @RequestParam(defaultValue = "10") Long size) {
        return Result.success(workflowService.pageRuns(id, page, size));
    }

    @Operation(summary = "运行节点级记录回放")
    @GetMapping("/runs/{runId}/nodes")
    public Result<List<Map<String, Object>>> nodeRuns(@PathVariable Long runId) {
        return Result.success(workflowService.listNodeRuns(runId));
    }
}
