package com.wgh.rag.kb.storage;

import java.util.List;

/**
 * 单次孤儿对象扫描结果。
 *
 * @param storageType       当前生效的存储类型（local / oss）
 * @param scanned           扫描到的存储对象数
 * @param referenced        数据库仍引用的 key 数（知识库文件 + 未完成上传会话分片）
 * @param orphans           无引用对象数
 * @param orphanBytes       无引用对象总字节数
 * @param deleted           delete=true 时实际删除数
 * @param deleteFailed      删除失败数
 * @param skippedYoung      未过删除保护期而跳过的对象数
 * @param missingReferences 数据库引用但存储中不存在的 key 数
 * @param durationMillis    耗时（毫秒）
 * @param sampleOrphans     孤儿 key 样例（条数受配置上限约束）
 */
public record StorageOrphanReport(String storageType, int scanned, int referenced, int orphans,
                                  long orphanBytes, int deleted, int deleteFailed, int skippedYoung,
                                  int missingReferences, long durationMillis, List<String> sampleOrphans) {
}
