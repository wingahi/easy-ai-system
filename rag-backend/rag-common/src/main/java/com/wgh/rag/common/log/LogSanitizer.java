package com.wgh.rag.common.log;

/**
 * 日志脱敏工具：敏感凭证（分享 token、嵌入授权、API Key）不得以明文写入日志。
 * 保留可定位的前后各 4 位，中间固定替换为 ***。
 */
public final class LogSanitizer {

    private static final int KEEP = 4;
    private static final String MASK = "***";

    private LogSanitizer() {
    }

    public static String maskSecret(String value) {
        if (value == null || value.isEmpty()) {
            return MASK;
        }
        if (value.length() <= KEEP * 2) {
            return MASK;
        }
        return value.substring(0, KEEP) + MASK + value.substring(value.length() - KEEP);
    }
}
