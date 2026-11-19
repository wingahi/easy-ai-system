package com.wgh.rag.storage.migration;

/**
 * 单个对象的迁移结果。
 */
public enum MigrationOutcome {
    /** 已搬运并校验通过 */
    COPIED,
    /** 目标已存在且大小/哈希一致，跳过 */
    SKIPPED,
    /** dry-run 下识别为待搬运 */
    PLANNED,
    /** 重试耗尽后失败 */
    FAILED
}
