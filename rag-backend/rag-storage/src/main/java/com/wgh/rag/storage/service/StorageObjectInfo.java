package com.wgh.rag.storage.service;

/**
 * 存储对象元数据（仅用于迁移、孤儿清理等运维场景）。
 *
 * @param key                逻辑 key，与 {@link StorageKeys} 规则一致，不含本地根目录或 OSS keyPrefix
 * @param size               对象字节数
 * @param lastModifiedMillis 最后修改时间（毫秒）；无法获取时为 0
 */
public record StorageObjectInfo(String key, long size, long lastModifiedMillis) {
}
