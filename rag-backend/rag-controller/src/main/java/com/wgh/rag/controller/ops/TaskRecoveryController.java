package com.wgh.rag.controller.ops;

import com.wgh.rag.auth.constant.AuthConstants;
import com.wgh.rag.common.result.Result;
import com.wgh.rag.common.security.RequiresPermission;
import com.wgh.rag.controller.service.TaskRecoveryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 未完成任务查询与继续执行。
 * <p>会话级接口校验会话归属；管理端接口按管理权限保护。</p>
 */
@Tag(name = "任务恢复")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class TaskRecoveryController {

    private final TaskRecoveryService taskRecoveryService;

    @Operation(summary = "查询会话未完成任务")
    @GetMapping("/chat/sessions/{sessionId}/pending-task")
    public Result<Map<String, Object>> pendingTask(@PathVariable Long sessionId) {
        return Result.success(taskRecoveryService.getPendingTask(sessionId));
    }

    @Operation(summary = "继续执行会话未完成任务")
    @PostMapping("/chat/sessions/{sessionId}/resume-task")
    public Result<Map<String, Object>> resumeTask(@PathVariable Long sessionId) {
        return Result.success(taskRecoveryService.resumeSessionTask(sessionId));
    }

    @Operation(summary = "分页查询运行中任务（三源合并）")
    @GetMapping("/admin/tasks/running")
    @RequiresPermission(AuthConstants.PERM_AGENT_MANAGE)
    public Result<Map<String, Object>> runningTasks(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long page,
            @RequestParam(required = false) Long size) {
        return Result.success(taskRecoveryService.listRunningTasks(type, status, page, size));
    }

    @Operation(summary = "管理端继续执行任务")
    @PostMapping("/admin/tasks/resume")
    @RequiresPermission(AuthConstants.PERM_AGENT_MANAGE)
    public Result<Map<String, Object>> resumeTask(@RequestBody Map<String, Object> body) {
        String taskType = body.get("taskType") == null ? null : String.valueOf(body.get("taskType"));
        String taskId = body.get("taskId") == null ? null : String.valueOf(body.get("taskId"));
        return Result.success(taskRecoveryService.resumeTask(taskType, taskId));
    }
}
