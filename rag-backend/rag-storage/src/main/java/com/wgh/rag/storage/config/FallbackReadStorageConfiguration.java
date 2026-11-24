package com.wgh.rag.storage.config;

import com.wgh.rag.storage.constant.StorageConstants;
import com.wgh.rag.storage.service.AliyunOssFileStorageService;
import com.wgh.rag.storage.service.FallbackReadFileStorageService;
import com.wgh.rag.storage.service.FileStorageService;
import com.wgh.rag.storage.service.StorageBackend;
import com.wgh.rag.storage.service.StorageBackendFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 迁移期兼容读装配：{@code wgh.rag.storage.read-fallback-enabled=true} 时，
 * 把当前 OSS 实现包装为「主存储 + 旧本地目录兜底读」的 {@link FileStorageService}。
 * <p>
 * 旧本地目录复用 {@code wgh.rag.storage.local.base-dir}（容器内通常仍是原 PVC 挂载路径），
 * 因此切换时无需额外配置路径；迁移完成并关闭开关后，PVC 即可摘除。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = StorageConstants.CONFIG_PREFIX, name = "read-fallback-enabled",
        havingValue = "true")
public class FallbackReadStorageConfiguration {

    @Bean
    @Primary
    public FileStorageService fallbackReadFileStorageService(
            ObjectProvider<AliyunOssFileStorageService> primaryProvider,
            StorageBackendFactory backendFactory) {
        AliyunOssFileStorageService primary = primaryProvider.getIfAvailable();
        if (primary == null) {
            throw new IllegalStateException("wgh.rag.storage.read-fallback-enabled=true 仅支持 type=oss；"
                    + "当前不是 oss 实现，请关闭该开关或先切换存储类型");
        }
        StorageBackend legacy = backendFactory.create(StorageConstants.TYPE_LOCAL);
        log.info("已启用迁移期兼容读：主存储 oss 未命中时回落本地目录读取");
        return new FallbackReadFileStorageService(primary, legacy.storage());
    }
}
