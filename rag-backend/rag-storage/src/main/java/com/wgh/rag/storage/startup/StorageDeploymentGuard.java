package com.wgh.rag.storage.startup;

import com.wgh.rag.storage.constant.StorageConstants;
import com.wgh.rag.storage.properties.StorageProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 多副本共享存储启动自检（{@code wgh.rag.storage.mode=shared}）。
 * <p>
 * {@code mode=single}（默认）不改变现有单实例行为，只输出部署模式；{@code mode=shared} 时：
 * <ul>
 *   <li>{@code type=oss}：OSS 必填项缺失直接启动失败，避免多副本静默写入无效后端；</li>
 *   <li>{@code type=local}：base-dir 必须可写，且在 Linux 下必须是独立挂载卷（不能是容器根盘），
 *       并执行一次写-读-删探测。校验失败直接启动失败，避免各副本各写各的本地盘。</li>
 * </ul>
 * 校验放启动阶段（ApplicationRunner）：失败时容器退出并由编排层暴露 CrashLoop，而不是带病提供服务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StorageDeploymentGuard implements ApplicationRunner {

    /** mountinfo 中的八进制转义，如空格编码为 \040 */
    private static final Pattern MOUNTINFO_ESCAPE = Pattern.compile("\\\\([0-7]{3})");

    private final StorageProperties properties;
    private final Environment environment;

    @Override
    public void run(ApplicationArguments args) {
        String mode = normalizeMode(properties.getMode());
        String type = normalizeType(properties.getType());

        if (properties.isReadFallbackEnabled() && !StorageConstants.TYPE_OSS.equals(type)) {
            log.warn("wgh.rag.storage.read-fallback-enabled=true 仅在 type=oss 时生效，当前 type={}，已忽略", type);
        }

        if (StorageConstants.MODE_SINGLE.equals(mode)) {
            log.info("文件存储部署模式: single（单实例）；本地目录 {} 不保证跨副本共享",
                    properties.getLocal().getBaseDir());
            return;
        }

        if (StorageConstants.TYPE_OSS.equals(type)) {
            List<String> missing = properties.getOss().missingConfigFields();
            if (!missing.isEmpty()) {
                throw new IllegalStateException("存储部署模式为 shared，但 OSS 配置不完整，缺少: "
                        + String.join(", ", missing) + "（wgh.rag.storage.oss.*）");
            }
            log.info("文件存储部署模式: shared；后端 oss，bucket={}", properties.getOss().getBucket());
            return;
        }

        validateSharedLocalVolume();
    }

    private void validateSharedLocalVolume() {
        Path baseDir;
        try {
            baseDir = Paths.get(properties.getLocal().getBaseDir()).toAbsolutePath().normalize();
            Files.createDirectories(baseDir);
            baseDir = baseDir.toRealPath();
        } catch (IOException e) {
            throw new IllegalStateException("共享存储校验失败：无法创建或访问本地根目录 "
                    + properties.getLocal().getBaseDir() + "（" + e.getMessage() + "）", e);
        }
        if (!Files.isWritable(baseDir)) {
            throw new IllegalStateException("共享存储校验失败：本地根目录不可写 " + baseDir);
        }

        Path mountPoint = nearestMountPoint(baseDir);
        if (mountPoint == null) {
            log.info("共享存储校验：当前平台无法读取挂载信息，跳过共享卷识别（base-dir={}）", baseDir);
        } else if (Path.of("/").equals(mountPoint) && !Path.of("/").equals(baseDir)) {
            throw new IllegalStateException("共享存储校验失败：base-dir=" + baseDir
                    + " 位于容器根文件系统而不是独立挂载卷；多副本请挂载 ReadWriteMany 共享卷，"
                    + "或改用 WGH_STORAGE_TYPE=oss");
        }

        probeWrite(baseDir);
        log.info("文件存储部署模式: shared；后端 local，base-dir={}，挂载点={}，写探测通过",
                baseDir, mountPoint == null ? "unknown" : mountPoint);
    }

    /**
     * 写-读-删探测：确认卷可写且读回内容一致；失败即启动失败。
     */
    private void probeWrite(Path baseDir) {
        String instance = sanitizeInstance(environment.getProperty("WGH_INSTANCE_ID",
                environment.getProperty("HOSTNAME", "unknown")));
        Path probe = baseDir.resolve(StorageConstants.PROBE_FILE_PREFIX + instance + ".probe");
        byte[] payload = ("wgh-storage-probe:" + instance).getBytes(StandardCharsets.UTF_8);
        try {
            Files.write(probe, payload, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            if (!Arrays.equals(payload, Files.readAllBytes(probe))) {
                throw new IllegalStateException("共享存储校验失败：写探测读回内容不一致 " + probe);
            }
        } catch (IOException e) {
            throw new IllegalStateException("共享存储校验失败：本地根目录不可写 " + baseDir
                    + "（" + e.getMessage() + "）", e);
        } finally {
            try {
                Files.deleteIfExists(probe);
            } catch (IOException e) {
                log.warn("清理共享存储探测文件失败: {} ({})", probe, e.getMessage());
            }
        }
    }

    /**
     * 取 base-dir 最近的挂载点；平台无 /proc/self/mountinfo 时返回 null（不做判断）。
     */
    private Path nearestMountPoint(Path dir) {
        Path mountInfo = Paths.get("/proc/self/mountinfo");
        if (!Files.isReadable(mountInfo)) {
            return null;
        }
        Path nearest = null;
        try {
            for (String line : Files.readAllLines(mountInfo, StandardCharsets.UTF_8)) {
                String[] fields = line.split(" ");
                if (fields.length < 5) {
                    continue;
                }
                Path mount = Paths.get(unescapeMountField(fields[4])).normalize();
                if (dir.startsWith(mount) && (nearest == null || mount.getNameCount() > nearest.getNameCount())) {
                    nearest = mount;
                }
            }
        } catch (IOException | RuntimeException e) {
            log.warn("读取挂载信息失败，跳过共享卷识别: {}", e.getMessage());
            return null;
        }
        return nearest;
    }

    private String unescapeMountField(String value) {
        Matcher matcher = MOUNTINFO_ESCAPE.matcher(value);
        StringBuilder result = new StringBuilder(value.length());
        while (matcher.find()) {
            char decoded = (char) Integer.parseInt(matcher.group(1), 8);
            matcher.appendReplacement(result, Matcher.quoteReplacement(String.valueOf(decoded)));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private String sanitizeInstance(String instanceId) {
        if (instanceId == null || instanceId.isBlank()) {
            return "unknown";
        }
        return instanceId.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private String normalizeMode(String mode) {
        String normalized = mode == null ? "" : mode.trim().toLowerCase(Locale.ROOT);
        if (!StorageConstants.MODE_SINGLE.equals(normalized) && !StorageConstants.MODE_SHARED.equals(normalized)) {
            throw new IllegalStateException("不支持的存储部署模式: " + mode
                    + "（wgh.rag.storage.mode，可选 single / shared）");
        }
        return normalized;
    }

    private String normalizeType(String type) {
        String normalized = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        if (!StorageConstants.TYPE_LOCAL.equals(normalized) && !StorageConstants.TYPE_OSS.equals(normalized)) {
            throw new IllegalStateException("不支持的存储类型: " + type
                    + "（wgh.rag.storage.type，可选 local / oss）");
        }
        return normalized;
    }
}
