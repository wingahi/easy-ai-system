package com.wgh.rag.controller.ops;

import com.wgh.rag.auth.constant.AuthConstants;
import com.wgh.rag.common.result.Result;
import com.wgh.rag.common.security.RequiresPermission;
import com.wgh.rag.controller.service.RunningTaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 管理端运行中任务查询（只读）。
 * <p>中断任务由调度中心按异步执行点处置，不再提供用户触发的继续执行入口。</p>
 */
@Tag(name = "运行中任务")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class RunningTaskController {

    private final RunningTaskService runningTaskService;

    @Operation(summary = "分页查询运行中任务（三源合并）")
    @GetMapping("/admin/tasks/running")
    @RequiresPermission(AuthConstants.PERM_AGENT_MANAGE)
    public Result<Map<String, Object>> runningTasks(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long page,
            @RequestParam(required = false) Long size) {
        return Result.success(runningTaskService.listRunningTasks(type, status, page, size));
    }
}
