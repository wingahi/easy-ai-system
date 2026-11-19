package com.wgh.rag.kb.storage;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 孤儿对象清理配置（代码默认值 + 环境变量，不改 application.yml）。
 * <p>
 * 可用环境变量：
 * {@code WGH_STORAGE_ORPHAN_CLEANUP_ENABLED}、{@code WGH_STORAGE_ORPHAN_CLEANUP_DELETE}、
 * {@code WGH_STORAGE_ORPHAN_CLEANUP_INTERVAL_MINUTES}、{@code WGH_STORAGE_ORPHAN_CLEANUP_MIN_AGE_MINUTES}、
 * {@code WGH_STORAGE_ORPHAN_CLEANUP_MAX_REPORT_ENTRIES}。
 * <p>
 * 默认只扫描并报告，不删除任何对象；必须显式 {@code delete=true} 才会执行删除。
 */
@Data
@Component
public class StorageOrphanCleanupProperties {

    /** 是否启用定期扫描（默认启用；首次执行在启动一个间隔之后）。 */
    @Value("${wgh.rag.storage.orphan-cleanup.enabled:true}")
    private boolean enabled = true;

    /** 是否真正删除孤儿对象；默认 false，仅输出报告。 */
    @Value("${wgh.rag.storage.orphan-cleanup.delete:false}")
    private boolean delete = false;

    /** 扫描间隔（分钟，最小 5）。 */
    @Value("${wgh.rag.storage.orphan-cleanup.interval-minutes:1440}")
    private int intervalMinutes = 1440;

    /** 删除保护期（分钟）：最后修改时间在该窗口内的对象只报告不删除，避免误删正在上传的对象。 */
    @Value("${wgh.rag.storage.orphan-cleanup.min-age-minutes:60}")
    private int minAgeMinutes = 60;

    /** 报告与逐条日志中保留的孤儿 key 条数上限。 */
    @Value("${wgh.rag.storage.orphan-cleanup.max-report-entries:100}")
    private int maxReportEntries = 100;
}
