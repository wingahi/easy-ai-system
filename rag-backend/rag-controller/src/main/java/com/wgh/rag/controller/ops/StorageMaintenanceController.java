package com.wgh.rag.controller.ops;

import com.wgh.rag.auth.constant.AuthConstants;
import com.wgh.rag.common.result.Result;
import com.wgh.rag.common.security.RequiresPermission;
import com.wgh.rag.kb.storage.MigrationJobView;
import com.wgh.rag.kb.storage.StorageMaintenanceService;
import com.wgh.rag.kb.storage.StorageOrphanReport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 存储运维：local ↔ oss 已有对象迁移、孤儿对象扫描/清理。
 * <p>
 * 迁移是异步作业：提交接口立即返回 taskId，通过查询接口轮询进度。任务状态保存在进程内，
 * 服务重启会丢失任务记录，但迁移可重新提交并依靠幂等语义续跑。
 */
@Tag(name = "存储运维")
@RestController
@RequestMapping("/api/admin/storage")
@RequiredArgsConstructor
@RequiresPermission(AuthConstants.PERM_AUDIT_VIEW)
public class StorageMaintenanceController {

    private final StorageMaintenanceService maintenanceService;

    @Operation(summary = "提交存储迁移任务（异步，立即返回 taskId；默认 dry-run）")
    @PostMapping("/migrate")
    public Result<MigrationJobView> migrate(@RequestParam String source,
                                            @RequestParam String target,
                                            @RequestParam(defaultValue = "true") boolean dryRun,
                                            @RequestParam(required = false) Integer concurrency,
                                            @RequestParam(required = false) Integer maxAttempts) {
        return Result.success(
                maintenanceService.submitMigration(source, target, dryRun, concurrency, maxAttempts));
    }

    @Operation(summary = "查询存储迁移任务实时进度")
    @GetMapping("/migrate/{taskId}")
    public Result<MigrationJobView> migrationJob(@PathVariable String taskId) {
        return Result.success(maintenanceService.getMigrationJob(taskId));
    }

    @Operation(summary = "按提交时间倒序列出最近的存储迁移任务")
    @GetMapping("/migrate/history")
    public Result<List<MigrationJobView>> migrationHistory(
            @RequestParam(defaultValue = "10") int limit) {
        return Result.success(maintenanceService.listMigrationJobs(limit));
    }

    @Operation(summary = "取消存储迁移任务（协作式：处理完当前对象后停止派发）")
    @PostMapping("/migrate/{taskId}/cancel")
    public Result<Boolean> cancelMigration(@PathVariable String taskId) {
        return Result.success(maintenanceService.cancelMigrationJob(taskId));
    }

    @Operation(summary = "孤儿对象扫描（默认仅报告，delete=true 才删除）")
    @PostMapping("/orphans/scan")
    public Result<StorageOrphanReport> scanOrphans(
            @RequestParam(defaultValue = "false") boolean delete) {
        return Result.success(maintenanceService.scanOrphans(delete));
    }
}
