package com.wgh.rag.storage.service;

import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.storage.constant.StorageConstants;
import com.wgh.rag.storage.properties.StorageProperties;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 本地磁盘实现（默认）：目录可配、防路径穿越、大小校验、temp → rename 原子写入。
 * <p>
 * 兼容历史数据：{@code wgh_knowledge_file.file_path} 中已存在的绝对路径或相对工作目录的历史路径，
 * 只要仍位于 {@code base-dir} 下即可继续读取；新数据统一写入 {@code kb/{kbId}/{uuid}/{fileName}}。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = StorageConstants.CONFIG_PREFIX, name = "type",
        havingValue = StorageConstants.TYPE_LOCAL, matchIfMissing = true)
public class LocalFileStorageService implements FileStorageService, StorageInventoryService {

    private final StorageProperties properties;
    private Path root;

    public LocalFileStorageService(StorageProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        root = Paths.get(properties.getLocal().getBaseDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
            log.info("文件存储使用本地实现，根目录: {}", root);
        } catch (IOException e) {
            // 目录创建失败不阻断启动，首次写入时会再抛出明确的业务异常
            log.warn("本地存储根目录创建失败: {} ({})", root, e.getMessage());
        }
    }

    @Override
    public StoredObject store(String key, InputStream in, long size, String contentType) {
        if (in == null) {
            throw new BusinessException("上传文件流为空");
        }
        long limit = maxBytes();
        if (size > limit) {
            throw new BusinessException(String.format("文件过大（%.1f MB），超过存储上限 %d MB",
                    size / 1024.0 / 1024.0, properties.getLocal().getMaxFileSizeMb()));
        }

        Path target = resolvePath(key);
        Path parent = target.getParent();
        Path temp = null;
        try {
            Files.createDirectories(parent);
            temp = Files.createTempFile(parent, StorageConstants.LOCAL_TEMP_PREFIX, StorageConstants.TEMP_SUFFIX);
            Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);

            long actualSize = Files.size(temp);
            if (actualSize <= 0) {
                throw new BusinessException("文件内容为空，无法存储");
            }
            if (actualSize > limit) {
                throw new BusinessException(String.format("文件过大（%.1f MB），超过存储上限 %d MB",
                        actualSize / 1024.0 / 1024.0, properties.getLocal().getMaxFileSizeMb()));
            }

            moveAtomically(temp, target);
            log.debug("本地存储写入成功: {} ({} bytes)", key, actualSize);
            return new StoredObject(key, actualSize, contentType);
        } catch (BusinessException e) {
            cleanupQuietly(temp);
            throw e;
        } catch (IOException e) {
            cleanupQuietly(temp);
            throw new BusinessException("文件保存失败: " + e.getMessage(), e);
        }
    }

    @Override
    public InputStream openStream(String key) {
        Path path = resolvePath(key);
        if (!Files.isRegularFile(path)) {
            throw new BusinessException("存储对象不存在: " + key);
        }
        try {
            return Files.newInputStream(path);
        } catch (IOException e) {
            throw new BusinessException("读取文件失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void delete(String key) {
        Path path = resolvePath(key);
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new BusinessException("删除文件失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String resolveUrl(String key) {
        String prefix = properties.getLocal().getUrlPrefix();
        if (prefix == null || prefix.isBlank()) {
            prefix = "";
        }
        if (prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        String normalizedKey = key == null ? "" : key.replace('\\', '/');
        if (normalizedKey.startsWith("/")) {
            normalizedKey = normalizedKey.substring(1);
        }
        return prefix + "/" + normalizedKey;
    }

    @Override
    public boolean exists(String key) {
        return Files.isRegularFile(resolvePath(key));
    }

    // ============ StorageInventoryService ============

    @Override
    public List<StorageObjectInfo> listAll() {
        return listByPrefix(null);
    }

    @Override
    public List<StorageObjectInfo> listByPrefix(String keyPrefix) {
        List<StorageObjectInfo> objects = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return objects;
        }
        Path start = root;
        if (keyPrefix != null && !keyPrefix.isBlank()) {
            try {
                start = resolvePath(keyPrefix);
            } catch (BusinessException e) {
                // 非法前缀按“无对象”处理，绝不回退到全量扫描
                log.warn("本地存储前缀非法，跳过列举: prefix={}, error={}", keyPrefix, e.getMessage());
                return objects;
            }
            if (!Files.isDirectory(start)) {
                return objects;
            }
        }
        try (Stream<Path> paths = Files.walk(start)) {
            paths.filter(Files::isRegularFile).forEach(path -> {
                String name = path.getFileName().toString();
                if (isTempFile(name)) {
                    return;
                }
                try {
                    objects.add(new StorageObjectInfo(
                            toLogicalKey(root.relativize(path)),
                            Files.size(path),
                            Files.getLastModifiedTime(path).toMillis()));
                } catch (IOException e) {
                    log.warn("读取本地存储对象元数据失败: {} ({})", path, e.getMessage());
                }
            });
        } catch (IOException e) {
            throw new BusinessException("列举本地存储对象失败: " + e.getMessage(), e);
        }
        return objects;
    }

    @Override
    public StorageObjectInfo stat(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        try {
            Path path = resolvePath(key);
            if (!Files.isRegularFile(path)) {
                return null;
            }
            return new StorageObjectInfo(key, Files.size(path), Files.getLastModifiedTime(path).toMillis());
        } catch (BusinessException e) {
            // 非法/越界 key 按“不存在”处理，避免运维扫描因单条异常中断
            log.debug("本地存储对象 stat 失败，按不存在处理: key={}, error={}", key, e.getMessage());
            return null;
        } catch (IOException e) {
            throw new BusinessException("读取本地存储对象元数据失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String canonicalKey(String storedKey) {
        if (storedKey == null || storedKey.isBlank()) {
            return "";
        }
        String normalized = storedKey.replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        try {
            Path raw = Paths.get(storedKey);
            Path candidate = null;
            if (raw.isAbsolute()) {
                candidate = raw.normalize();
            } else if (!normalized.startsWith(StorageConstants.KEY_PREFIX_KB)) {
                // 历史数据兼容：./uploads/1/a.pdf 这类相对工作目录路径
                candidate = raw.toAbsolutePath().normalize();
            }
            if (candidate != null && candidate.startsWith(root)) {
                return toLogicalKey(root.relativize(candidate));
            }
        } catch (RuntimeException e) {
            log.debug("历史存储路径无法规范化，按原值处理: {} ({})", storedKey, e.getMessage());
        }
        return normalized;
    }

    private String toLogicalKey(Path path) {
        return path.toString().replace(File.separatorChar, '/');
    }

    private boolean isTempFile(String fileName) {
        return fileName.startsWith(StorageConstants.LOCAL_TEMP_PREFIX)
                && fileName.endsWith(StorageConstants.TEMP_SUFFIX);
    }

    private long maxBytes() {
        return (long) Math.max(1, properties.getLocal().getMaxFileSizeMb()) * StorageConstants.BYTES_PER_MB;
    }

    /**
     * 解析并校验 key，禁止越过存储根目录。
     */
    private Path resolvePath(String key) {
        if (key == null || key.isBlank()) {
            throw new BusinessException("存储 key 不能为空");
        }
        Path raw = Paths.get(key);
        if (raw.isAbsolute()) {
            return requireUnderRoot(raw.normalize(), key);
        }

        // 历史数据兼容：旧实现可能把 ./uploads/1/a.pdf 这类相对工作目录路径直接写库
        if (!key.startsWith(StorageConstants.KEY_PREFIX_KB)) {
            Path legacy = Paths.get(key).toAbsolutePath().normalize();
            if (legacy.startsWith(root)) {
                return legacy;
            }
        }

        return requireUnderRoot(root.resolve(key).normalize(), key);
    }

    private Path requireUnderRoot(Path path, String key) {
        if (!path.startsWith(root)) {
            throw new BusinessException("非法的存储 key（路径穿越）: " + key);
        }
        return path;
    }

    private void moveAtomically(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void cleanupQuietly(Path temp) {
        if (temp == null) {
            return;
        }
        try {
            Files.deleteIfExists(temp);
        } catch (IOException e) {
            log.warn("清理临时文件失败: {} ({})", temp, e.getMessage());
        }
    }
}
