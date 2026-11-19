package com.wgh.rag.storage.service;

import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.storage.constant.StorageConstants;
import com.wgh.rag.storage.properties.StorageProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * 按存储类型临时创建后端实例，供 local ↔ oss 迁移使用。
 * <p>
 * 当前生效的存储 Bean 由 {@code @ConditionalOnProperty} 二选一装配；迁移需要在同一进程中同时
 * 访问源与目标，因此这里不使用 Spring 容器中的 Bean，而是基于同一份 {@link StorageProperties}
 * 新建实例。实例用完必须关闭，避免 OSS 客户端泄漏。
 */
@Component
@RequiredArgsConstructor
public class StorageBackendFactory {

    private final StorageProperties properties;

    public StorageBackend create(String type) {
        String normalized = normalizeType(type);
        if (StorageConstants.TYPE_LOCAL.equals(normalized)) {
            LocalFileStorageService service = new LocalFileStorageService(properties);
            service.init();
            return new StorageBackend(StorageConstants.TYPE_LOCAL, service, service, null);
        }
        if (StorageConstants.TYPE_OSS.equals(normalized)) {
            List<String> missing = properties.getOss().missingConfigFields();
            if (!missing.isEmpty()) {
                throw new BusinessException("OSS 存储未配置完整，缺少: " + String.join(", ", missing)
                        + "（wgh.rag.storage.oss.*）");
            }
            AliyunOssFileStorageService service = new AliyunOssFileStorageService(properties);
            service.init();
            return new StorageBackend(StorageConstants.TYPE_OSS, service, service, service::shutdown);
        }
        throw new BusinessException("不支持的存储类型: " + type);
    }

    private String normalizeType(String type) {
        if (type == null || type.isBlank()) {
            throw new BusinessException("存储类型不能为空");
        }
        String normalized = type.trim().toLowerCase(Locale.ROOT);
        if (!StorageConstants.TYPE_LOCAL.equals(normalized) && !StorageConstants.TYPE_OSS.equals(normalized)) {
            throw new BusinessException("不支持的存储类型: " + type);
        }
        return normalized;
    }
}
