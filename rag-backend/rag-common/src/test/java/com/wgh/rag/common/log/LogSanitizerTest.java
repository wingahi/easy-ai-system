package com.wgh.rag.common.log;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 日志脱敏测试：断言敏感凭证不会以明文进入日志消息。
 */
class LogSanitizerTest {

    @Test
    @DisplayName("LOG-01 长凭证仅保留首尾 4 位，中间以 *** 替换")
    void maskLongSecret() {
        String token = "share-token-0123456789abcdef";
        String masked = LogSanitizer.maskSecret(token);

        assertEquals("shar***cdef", masked);
        assertFalse(masked.contains("token-0123456789abc"), "日志不得包含凭证明文主体");
    }

    @Test
    @DisplayName("LOG-02 短凭证整体替换为 ***")
    void maskShortSecret() {
        assertEquals("***", LogSanitizer.maskSecret("abc"));
        assertEquals("***", LogSanitizer.maskSecret("12345678"));
    }

    @Test
    @DisplayName("LOG-03 null/空值输出 ***，避免日志出现 null 或空串")
    void maskNullAndEmpty() {
        assertEquals("***", LogSanitizer.maskSecret(null));
        assertEquals("***", LogSanitizer.maskSecret(""));
    }
}
