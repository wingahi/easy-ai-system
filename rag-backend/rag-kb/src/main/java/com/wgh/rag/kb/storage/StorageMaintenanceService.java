package com.wgh.rag.kb.storage;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 存储运维能力入口：为 Controller 屏蔽 storage 模块的实现细节。
 */
@Service
@RequiredArgsConstructor
public class StorageMaintenanceService {

    private final StorageMigrationJobService migrationJobService;
    private final StorageOrphanCleanupService orphanCleanupService;

    /** 提交异步迁移任务，立即返回任务视图（含 taskId）。 */
    public MigrationJobView submitMigration(String sourceType, String targetType, boolean dryRun,
                                            Integer concurrency, Integer maxAttempts) {
        return migrationJobService.submit(sourceType, targetType, dryRun, concurrency, maxAttempts);
    }

    /** 查询迁移任务实时进度。 */
    public MigrationJobView getMigrationJob(String taskId) {
        return migrationJobService.get(taskId);
    }

    /** 按提交时间倒序列出最近的迁移任务。 */
    public List<MigrationJobView> listMigrationJobs(int limit) {
        return migrationJobService.listRecent(limit);
    }

    /** 请求取消迁移任务（协作式）。 */
    public boolean cancelMigrationJob(String taskId) {
        return migrationJobService.cancel(taskId);
    }

    /**
     * 按租户范围扫描；禁止全局扫描推断。
     */
    public StorageOrphanReport scanOrphans(String tenantId, boolean delete) {
        return orphanCleanupService.scan(tenantId, delete);
    }
}
