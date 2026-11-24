package com.wgh.rag.storage.service;

import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;

/**
 * 迁移期兼容读装饰器：主存储（OSS）未命中时回落读取旧本地目录。
 * <p>
 * 用于 {@code local → oss} 滚动切换窗口：已有对象的 {@code file_path} 不变，迁移完成前由旧本地目录兜底读取，
 * 新写入一律进主存储。删除同时清理两侧，避免旧对象残留；{@code resolveUrl} 只按主存储生成。
 * <p>
 * 仅当 {@code wgh.rag.storage.read-fallback-enabled=true} 且 {@code type=oss} 时装配，默认关闭。
 */
@Slf4j
public class FallbackReadFileStorageService implements FileStorageService {

    private final FileStorageService primary;
    private final FileStorageService legacy;

    public FallbackReadFileStorageService(FileStorageService primary, FileStorageService legacy) {
        this.primary = primary;
        this.legacy = legacy;
    }

    @Override
    public StoredObject store(String key, InputStream in, long size, String contentType) {
        return primary.store(key, in, size, contentType);
    }

    @Override
    public InputStream openStream(String key) {
        if (!primary.exists(key) && legacy.exists(key)) {
            log.debug("主存储未命中，回落旧本地目录读取: {}", key);
            return legacy.openStream(key);
        }
        return primary.openStream(key);
    }

    @Override
    public void delete(String key) {
        RuntimeException failure = null;
        try {
            primary.delete(key);
        } catch (RuntimeException e) {
            failure = e;
        }
        try {
            if (legacy.exists(key)) {
                legacy.delete(key);
            }
        } catch (RuntimeException e) {
            log.warn("清理旧本地对象失败（可能残留）: {} ({})", key, e.getMessage());
        }
        if (failure != null) {
            throw failure;
        }
    }

    @Override
    public String resolveUrl(String key) {
        return primary.resolveUrl(key);
    }

    @Override
    public boolean exists(String key) {
        return primary.exists(key) || legacy.exists(key);
    }
}
