package com.wgh.rag.storage.service;

/**
 * 迁移使用的存储后端组合：读写能力 + 盘点能力 + 资源释放动作。
 * <p>
 * 迁移时源/目标可能都不是当前生效的 Spring Bean，因此由 {@link StorageBackendFactory}
 * 按类型临时创建，使用完毕后必须 {@link #close()} 释放（OSS 客户端会在此关闭）。
 */
public record StorageBackend(String type, FileStorageService storage,
                             StorageInventoryService inventory, Runnable closeAction)
        implements AutoCloseable {

    @Override
    public void close() {
        if (closeAction != null) {
            closeAction.run();
        }
    }
}
