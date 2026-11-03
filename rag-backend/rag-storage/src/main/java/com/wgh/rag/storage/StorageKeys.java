package com.wgh.rag.storage;

/**
 * 存储 key 规则：{@code kb/{kbId}/{fileUuid}/{安全化后的原始文件名}}。
 * <p>
 * 同一知识库下每次上传使用独立 UUID 目录，避免同名覆盖；文件名仅保留 basename 并替换非法字符，
 * 保证 key 可稳定推导、不含路径分隔符，也不会引入 {@code ..} 路径穿越。
 */
public final class StorageKeys {

    private static final String KB_PREFIX = "kb/";

    private StorageKeys() {
    }

    public static String kbKey(Long kbId, String fileUuid, String originalFileName) {
        if (kbId == null) {
            throw new IllegalArgumentException("kbId 不能为空");
        }
        String uuid = fileUuid == null || fileUuid.isBlank() ? "unknown" : fileUuid.trim();
        return KB_PREFIX + kbId + "/" + uuid + "/" + sanitizeFileName(originalFileName);
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
