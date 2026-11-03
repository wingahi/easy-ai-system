package com.wgh.rag.storage.service;

import com.wgh.rag.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * key 规则：{@code kb/{kbId}/{uuid}/{fileName}}，文件名安全化后不可含路径分隔符。
 */
class StorageKeysTest {

    @Test
    @DisplayName("正常文件名保留中文与扩展名")
    void buildKey() {
        assertThat(StorageKeys.kbKey(7L, "uuid", "采购制度.pdf"))
                .isEqualTo("kb/7/uuid/采购制度.pdf");
    }

    @Test
    @DisplayName("客户端携带目录的文件名只保留 basename")
    void stripClientPath() {
        assertThat(StorageKeys.kbKey(1L, "u", "../../etc/passwd"))
                .isEqualTo("kb/1/u/passwd");
        assertThat(StorageKeys.sanitizeFileName("C:\\temp\\a.docx")).isEqualTo("a.docx");
    }

    @Test
    @DisplayName("非法/空文件名回退为 file")
    void fallbackName() {
        assertThat(StorageKeys.sanitizeFileName(null)).isEqualTo("file");
        assertThat(StorageKeys.sanitizeFileName("..")).isEqualTo("file");
        assertThat(StorageKeys.sanitizeFileName("   ")).isEqualTo("file");
    }

    @Test
    @DisplayName("kbId 为空时拒绝构造 key")
    void requireKbId() {
        assertThatThrownBy(() -> StorageKeys.kbKey(null, "u", "a.txt"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
