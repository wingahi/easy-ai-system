package com.wgh.rag.storage.service;

import com.wgh.rag.common.security.TenantContext;
import com.wgh.rag.storage.constant.StorageConstants;

import java.util.Locale;

/**
 * 存储 key 规则：{@code kb/{kbId}/{fileUuid}/{安全化后的原始文件名}}。
 * <p>
 * 多租户隔离：默认租户沿用历史 {@code kb/...} 前缀（存量对象零迁移）；
 * 其他租户使用 {@code t/{tenantId}/kb/...}。租户前缀是清理扫描的收敛边界，
 * 不允许从对象 key 反推租户之外的任何信息。
 * <p>
 * 同一知识库下每次上传使用独立 UUID 目录，避免同名覆盖；文件名仅保留 basename 并替换非法字符，
 * 保证 key 可稳定推导、不含路径分隔符，也不会引入 {@code ..} 路径穿越。
 */
public final class StorageKeys {

    private static final String KB_PREFIX = StorageConstants.KEY_PREFIX_KB;
    private static final String TENANT_ROOT = "t/";
    private static final String TENANT_PATTERN = "[a-z0-9][a-z0-9_-]{0,63}";

    private StorageKeys() {
    }

    /** 默认租户沿用历史 key 规则。 */
    public static String kbKey(Long kbId, String fileUuid, String originalFileName) {
        return kbKey(null, kbId, fileUuid, originalFileName);
    }

    /** 租户化文件 key；tenantId 为空时按默认租户处理。 */
    public static String kbKey(String tenantId, Long kbId, String fileUuid, String originalFileName) {
        if (kbId == null) {
            throw new IllegalArgumentException("kbId 不能为空");
        }
        String uuid = fileUuid == null || fileUuid.isBlank() ? "unknown" : fileUuid.trim();
        return tenantPrefix(tenantId) + kbId + "/" + uuid + "/" + sanitizeFileName(originalFileName);
    }

    /** 默认租户分片 key（历史规则）。 */
    public static String chunkKey(Long kbId, String uploadUuid, int index) {
        return chunkKey(null, kbId, uploadUuid, index);
    }

    /**
     * 租户化分片 key：{@code [t/{tenantId}/]kb/{kbId}/{uploadUuid}/parts/{index}.part}。
     * <p>
     * 与正式文件同处一个上传目录下，合并完成后整体清理该目录的分片对象。
     */
    public static String chunkKey(String tenantId, Long kbId, String uploadUuid, int index) {
        if (kbId == null) {
            throw new IllegalArgumentException("kbId 不能为空");
        }
        if (uploadUuid == null || uploadUuid.isBlank()) {
            throw new IllegalArgumentException("uploadUuid 不能为空");
        }
        if (index < 0) {
            throw new IllegalArgumentException("分片序号不能为负");
        }
        return tenantPrefix(tenantId) + kbId + "/" + uploadUuid.trim() + "/parts/" + index + ".part";
    }

    /**
     * 租户扫描前缀：默认租户为 {@code kb/}，其他租户为 {@code t/{tenantId}/kb/}。
     * <p>孤儿清理只允许在该前缀内列举与删除，禁止全库推断。
     */
    public static String tenantPrefix(String tenantId) {
        String normalized = normalizeTenant(tenantId);
        if (TenantContext.DEFAULT_TENANT.equals(normalized)) {
            return KB_PREFIX;
        }
        return TENANT_ROOT + normalized + "/" + KB_PREFIX;
    }

    /**
     * 从对象 key 解析归属租户；无法确定时返回 {@code null}（调用方必须跳过处理）。
     */
    public static String tenantOfKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String normalized = key.replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.startsWith(KB_PREFIX)) {
            return TenantContext.DEFAULT_TENANT;
        }
        if (!normalized.startsWith(TENANT_ROOT)) {
            return null;
        }
        int end = normalized.indexOf('/', TENANT_ROOT.length());
        if (end <= TENANT_ROOT.length()) {
            return null;
        }
        String tenant = normalized.substring(TENANT_ROOT.length(), end);
        return tenant.matches(TENANT_PATTERN) ? tenant : null;
    }

    /** key 是否可明确归属到指定租户。 */
    public static boolean belongsTo(String key, String tenantId) {
        String owner = tenantOfKey(key);
        return owner != null && owner.equals(normalizeTenant(tenantId));
    }

    private static String normalizeTenant(String tenantId) {
        String tenant = tenantId == null || tenantId.isBlank()
                ? TenantContext.DEFAULT_TENANT : tenantId.trim().toLowerCase(Locale.ROOT);
        if (!tenant.matches(TENANT_PATTERN)) {
            throw new IllegalArgumentException("非法租户编码: " + tenantId);
        }
        return tenant;
    }

    /**
     * 仅保留文件名本身（去掉客户端可能携带的目录），并把路径分隔符、空白等替换为下划线。
     */
    public static String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "file";
        }
        int slash = Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'));
        String base = slash >= 0 ? fileName.substring(slash + 1) : fileName;
        base = base.trim();
        if (base.isEmpty() || base.equals(".") || base.equals("..")) {
            return "file";
        }
        return base.replaceAll("[\\\\/:*?\"<>|\\s]", "_");
    }
}
