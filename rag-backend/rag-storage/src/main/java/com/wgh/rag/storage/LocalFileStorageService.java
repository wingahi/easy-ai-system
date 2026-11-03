package com.wgh.rag.storage;

import com.wgh.rag.common.exception.BusinessException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * 本地磁盘实现（默认）：目录可配、防路径穿越、大小校验、temp → rename 原子写入。
 * <p>
 * 兼容历史数据：{@code wgh_knowledge_file.file_path} 中已存在的绝对路径或相对工作目录的历史路径，
 * 只要仍位于 {@code base-dir} 下即可继续读取；新数据统一写入 {@code kb/{kbId}/{uuid}/{fileName}}。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "wgh.rag.storage", name = "type", havingValue = "local", matchIfMissing = true)
public class LocalFileStorageService implements FileStorageService {

    private final StorageProperties properties;
    private Path root;

    public LocalFileStorageService(StorageProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void init() {
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
            temp = Files.createTempFile(parent, ".upload-", ".part");
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

    private long maxBytes() {
        return (long) Math.max(1, properties.getLocal().getMaxFileSizeMb()) * 1024 * 1024;
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
        if (!key.startsWith("kb/")) {
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
