package com.wgh.rag.storage.service;

import java.util.List;

/**
 * 存储对象盘点能力：只服务于迁移与孤儿清理，业务读写仍走 {@link FileStorageService}。
 * <p>
 * 与 {@link FileStorageService} 由同一个存储实现提供（local / OSS 二选一），
 * 返回的 key 统一为逻辑 key，便于与数据库中的 {@code file_path} 直接比较。
 */
public interface StorageInventoryService {

    /**
     * 列出当前存储后端中的全部对象。
     * <ul>
     *   <li>本地：递归遍历存储根目录，key 为相对根目录的路径；</li>
     *   <li>OSS：按配置的 keyPrefix 分页列举，返回去除前缀后的逻辑 key。</li>
     * </ul>
     * 本地写入过程中的临时文件（{@code .upload-*.part}）不会出现在结果中。
     */
    List<StorageObjectInfo> listAll();

    /**
     * 查询单个对象元数据，不存在时返回 {@code null}。
     */
    StorageObjectInfo stat(String key);

    /**
     * 把数据库中可能保存的历史路径（绝对路径、相对工作目录路径、带 {@code ./} 前缀）
     * 转换为与 {@link #listAll()} 一致的可比较 key；无法识别时返回规范化后的原值。
     */
    String canonicalKey(String storedKey);
}
