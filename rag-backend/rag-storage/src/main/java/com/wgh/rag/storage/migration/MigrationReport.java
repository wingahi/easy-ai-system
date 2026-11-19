package com.wgh.rag.storage.migration;

import java.util.List;

/**
 * 单次存储迁移的结果摘要。
 *
 * @param sourceType   源存储类型（local / oss）
 * @param targetType   目标存储类型（local / oss）
 * @param dryRun       是否仅演练未写入
 * @param total        扫描到的源对象总数
 * @param copied       实际搬运成功数
 * @param skipped      目标已存在且大小/哈希一致而跳过数（断点续跑的主要来源）
 * @param planned      dry-run 模式下待搬运数
 * @param failed       最终失败数（已重试耗尽）
 * @param copiedBytes  实际搬运字节数
 * @param durationMillis 耗时（毫秒）
 * @param failures     失败对象摘要（key: error），条数受配置上限约束
 */
public record MigrationReport(String sourceType, String targetType, boolean dryRun, int total,
                              int copied, int skipped, int planned, int failed, long copiedBytes,
                              long durationMillis, List<String> failures) {
}
