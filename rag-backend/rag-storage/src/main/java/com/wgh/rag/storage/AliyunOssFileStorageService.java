package com.wgh.rag.storage;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.ObjectMetadata;
import com.wgh.rag.common.exception.BusinessException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Date;
import java.util.List;
import java.util.function.Supplier;

/**
 * 阿里云 OSS 实现，通过 {@code wgh.rag.storage.type=oss} 装配。
 * <p>
 * 服务端读写优先使用内网 endpoint；签名/公网 URL 使用公网 endpoint。
 * 配置为空时不阻断应用启动，仅在真正调用存储操作时返回明确的业务错误（并输出 WARN）。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "wgh.rag.storage", name = "type", havingValue = "oss")
public class AliyunOssFileStorageService implements FileStorageService {

    private final StorageProperties properties;
    /** 测试注入用；生产为 null 时按配置懒创建 */
    private final OSS injectedClient;

    private volatile OSS serverClient;
    private volatile OSS urlClient;
    private volatile OSS createdServerClient;
    private volatile OSS createdUrlClient;

    @Autowired
    public AliyunOssFileStorageService(StorageProperties properties) {
        this(properties, null);
    }

    AliyunOssFileStorageService(StorageProperties properties, OSS injectedClient) {
        this.properties = properties;
        this.injectedClient = injectedClient;
    }

    @PostConstruct
    void init() {
        StorageProperties.Oss oss = properties.getOss();
        List<String> missing = oss.missingConfigFields();
        if (!missing.isEmpty()) {
            log.warn("文件存储类型为 oss，但配置不完整（缺少 {}），应用仍可启动；"
                    + "上传/读取/删除会返回明确错误。请补齐 wgh.rag.storage.oss.* 配置。", missing);
        } else {
            log.info("文件存储使用阿里云 OSS: bucket={}, endpoint={}, internalEndpoint={}, keyPrefix={}",
                    oss.getBucket(), oss.getEndpoint(), oss.getInternalEndpoint(), oss.getKeyPrefix());
        }
    }

    @PreDestroy
    void shutdown() {
        if (createdServerClient != null) {
            createdServerClient.shutdown();
            createdServerClient = null;
        }
        if (createdUrlClient != null && createdUrlClient != createdServerClient) {
            createdUrlClient.shutdown();
        }
        createdUrlClient = null;
        serverClient = null;
        urlClient = null;
    }

    @Override
    public StoredObject store(String key, InputStream in, long size, String contentType) {
        if (in == null) {
            throw new BusinessException("上传文件流为空");
        }
        ensureConfigured();
        long limit = maxBytes();
        if (size > limit) {
            throw new BusinessException(String.format("文件过大（%.1f MB），超过存储上限 %d MB",
                    size / 1024.0 / 1024.0, properties.getOss().getMaxFileSizeMb()));
        }

        String objectKey = objectKey(key);
        Path spool = spoolToTemp(in, limit);
        boolean success = false;
        try {
            long actualSize = Files.size(spool);
            execute("上传", () -> {
                try (InputStream upload = Files.newInputStream(spool)) {
                    ObjectMetadata metadata = new ObjectMetadata();
                    metadata.setContentLength(actualSize);
                    if (contentType != null && !contentType.isBlank()) {
                        metadata.setContentType(contentType);
                    }
                    serverClient().putObject(properties.getOss().getBucket(), objectKey, upload, metadata);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                return null;
            });
            success = true;
            log.debug("OSS 写入成功: {} ({} bytes)", objectKey, actualSize);
            return new StoredObject(key, actualSize, contentType);
        } catch (IOException e) {
            throw new BusinessException("读取上传临时文件失败: " + e.getMessage(), e);
        } finally {
            deleteQuietly(spool);
            if (!success) {
                // 上传失败清理，避免半成品/重试残留
                deleteObjectQuietly(objectKey);
            }
        }
    }

    @Override
    public InputStream openStream(String key) {
        ensureConfigured();
        String objectKey = objectKey(key);
        return execute("读取", () -> {
            var object = serverClient().getObject(properties.getOss().getBucket(), objectKey);
            if (object == null || object.getObjectContent() == null) {
                throw new BusinessException("存储对象不存在: " + key);
            }
            return object.getObjectContent();
        });
    }

    @Override
    public void delete(String key) {
        ensureConfigured();
        String objectKey = objectKey(key);
        execute("删除", () -> {
            serverClient().deleteObject(properties.getOss().getBucket(), objectKey);
            return null;
        });
    }

    @Override
    public String resolveUrl(String key) {
        ensureConfigured();
        String objectKey = objectKey(key);
        StorageProperties.Oss oss = properties.getOss();
        if (oss.isPublicRead()) {
            return publicUrl(objectKey);
        }
        int expireMinutes = Math.max(1, oss.getSignedUrlExpireMinutes());
        Date expiration = new Date(System.currentTimeMillis() + expireMinutes * 60_000L);
        return execute("生成签名 URL",
                () -> urlClient().generatePresignedUrl(oss.getBucket(), objectKey, expiration).toString());
    }

    @Override
    public boolean exists(String key) {
        ensureConfigured();
        String objectKey = objectKey(key);
        return execute("检查对象",
                () -> serverClient().doesObjectExist(properties.getOss().getBucket(), objectKey));
    }

    // ============ 私有方法 ============

    private void ensureConfigured() {
        List<String> missing = properties.getOss().missingConfigFields();
        if (!missing.isEmpty()) {
            throw new BusinessException("OSS 存储未配置完整，缺少: " + String.join(", ", missing)
                    + "（wgh.rag.storage.oss.*）");
        }
    }

    private long maxBytes() {
        return (long) Math.max(1, properties.getOss().getMaxFileSizeMb()) * 1024 * 1024;
    }

    private String objectKey(String key) {
        if (key == null || key.isBlank()) {
            throw new BusinessException("存储 key 不能为空");
        }
        String normalized = key.replace('\\', '/');
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        String prefix = properties.getOss().getKeyPrefix();
        if (prefix == null || prefix.isBlank()) {
            return normalized;
        }
        prefix = prefix.replace('\\', '/').replaceAll("^/+", "");
        if (!prefix.endsWith("/")) {
            prefix = prefix + "/";
        }
        return prefix + normalized;
    }

    private Path spoolToTemp(InputStream in, long limit) {
        Path temp = null;
        try {
            temp = Files.createTempFile("wgh-oss-upload-", ".part");
            Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            long actual = Files.size(temp);
            if (actual <= 0) {
                throw new BusinessException("文件内容为空，无法存储");
            }
            if (actual > limit) {
                throw new BusinessException(String.format("文件过大（%.1f MB），超过存储上限 %d MB",
                        actual / 1024.0 / 1024.0, properties.getOss().getMaxFileSizeMb()));
            }
            return temp;
        } catch (BusinessException e) {
            deleteQuietly(temp);
            throw e;
        } catch (IOException e) {
            deleteQuietly(temp);
            throw new BusinessException("上传文件暂存失败: " + e.getMessage(), e);
        }
    }

    private OSS serverClient() {
        if (injectedClient != null) {
            return injectedClient;
        }
        if (serverClient == null) {
            synchronized (this) {
                if (serverClient == null) {
                    StorageProperties.Oss oss = properties.getOss();
                    String endpoint = isBlank(oss.getInternalEndpoint())
                            ? oss.getEndpoint() : oss.getInternalEndpoint();
                    createdServerClient = new OSSClientBuilder()
                            .build(endpoint, oss.getAccessKeyId(), oss.getAccessKeySecret());
                    serverClient = createdServerClient;
                }
            }
        }
        return serverClient;
    }

    private OSS urlClient() {
        if (injectedClient != null) {
            return injectedClient;
        }
        StorageProperties.Oss oss = properties.getOss();
        String serverEndpoint = isBlank(oss.getInternalEndpoint())
                ? oss.getEndpoint() : oss.getInternalEndpoint();
        if (serverEndpoint.equals(oss.getEndpoint())) {
            return serverClient();
        }
        if (urlClient == null) {
            synchronized (this) {
                if (urlClient == null) {
                    createdUrlClient = new OSSClientBuilder()
                            .build(oss.getEndpoint(), oss.getAccessKeyId(), oss.getAccessKeySecret());
                    urlClient = createdUrlClient;
                }
            }
        }
        return urlClient;
    }

    private String publicUrl(String objectKey) {
        StorageProperties.Oss oss = properties.getOss();
        String endpoint = oss.getEndpoint().trim();
        String scheme = "https";
        String host = endpoint;
        if (endpoint.contains("://")) {
            URI uri = URI.create(endpoint);
            scheme = uri.getScheme();
            host = uri.getHost();
        } else if (endpoint.endsWith("/")) {
            host = endpoint.substring(0, endpoint.length() - 1);
        }
        return scheme + "://" + oss.getBucket() + "." + host + "/" + objectKey;
    }

    private <T> T execute(String operation, Supplier<T> action) {
        int attempts = Math.max(1, properties.getOss().getMaxRetries());
        RuntimeException last = null;
        for (int i = 1; i <= attempts; i++) {
            try {
                return action.get();
            } catch (RuntimeException e) {
                last = e;
                log.warn("OSS {} 第 {}/{} 次失败: {}", operation, i, attempts, e.getMessage());
            }
        }
        throw new BusinessException("OSS " + operation + "失败: "
                + (last != null && last.getMessage() != null ? last.getMessage() : "unknown"), last);
    }

    private void deleteObjectQuietly(String objectKey) {
        try {
            serverClient().deleteObject(properties.getOss().getBucket(), objectKey);
        } catch (RuntimeException e) {
            log.warn("OSS 上传失败后清理对象失败: {} ({})", objectKey, e.getMessage());
        }
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("清理临时文件失败: {} ({})", path, e.getMessage());
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
