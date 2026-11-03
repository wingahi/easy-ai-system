package com.wgh.rag.storage;

import com.wgh.rag.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 本地存储实现：写入/读取/删除/不存在/路径穿越/大小超限/历史绝对路径兼容。
 */
class LocalFileStorageServiceTest {

    @TempDir
    Path tempDir;

    private LocalFileStorageService service;

    @BeforeEach
    void setUp() {
        StorageProperties properties = new StorageProperties();
        properties.getLocal().setBaseDir(tempDir.toString());
        properties.getLocal().setUrlPrefix("/files");
        properties.getLocal().setMaxFileSizeMb(1);
        service = new LocalFileStorageService(properties);
        service.init();
    }

    @Test
    @DisplayName("写入后可读取、存在性判断与 URL 解析")
    void storeOpenExistsAndResolveUrl() throws IOException {
        String key = StorageKeys.kbKey(1L, "uuid-1", "制度 说明.pdf");
        byte[] content = "hello storage".getBytes(StandardCharsets.UTF_8);

        StoredObject stored = service.store(key, new ByteArrayInputStream(content), content.length, "application/pdf");

        assertThat(stored.key()).isEqualTo(key);
        assertThat(stored.size()).isEqualTo(content.length);
        assertThat(service.exists(key)).isTrue();
        try (InputStream in = service.openStream(key)) {
            assertThat(in.readAllBytes()).isEqualTo(content);
        }
        assertThat(service.resolveUrl(key)).isEqualTo("/files/" + key);
        // 原子写入：目录内不残留 .part 临时文件
        Path parent = tempDir.resolve(key).getParent();
        try (var files = Files.list(parent)) {
            assertThat(files).noneMatch(p -> p.getFileName().toString().endsWith(".part"));
        }
    }

    @Test
    @DisplayName("删除幂等：存在时删除，重复删除不报错")
    void deleteIsIdempotent() throws IOException {
        String key = "kb/2/uuid-2/a.txt";
        byte[] content = "x".getBytes(StandardCharsets.UTF_8);
        service.store(key, new ByteArrayInputStream(content), content.length, "text/plain");

        service.delete(key);
        assertThat(service.exists(key)).isFalse();
        service.delete(key);
        assertThat(service.exists(key)).isFalse();
    }

    @Test
    @DisplayName("读取不存在的对象抛出明确业务异常")
    void openMissingObject() {
        assertThatThrownBy(() -> service.openStream("kb/3/uuid-3/missing.txt"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("存储对象不存在");
    }

    @Test
    @DisplayName("路径穿越：写入/读取越界 key 均被拒绝")
    void rejectPathTraversal() {
        assertThatThrownBy(() -> service.store("../evil.txt",
                new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)), 1, "text/plain"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("路径穿越");

        assertThatThrownBy(() -> service.openStream("../../etc/passwd"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("路径穿越");
    }

    @Test
    @DisplayName("大小超限：超过配置上限的流被拒绝且不落盘")
    void rejectOversize() {
        byte[] content = new byte[1024 * 1024 + 1];
        String key = "kb/4/uuid-4/big.bin";

        assertThatThrownBy(() -> service.store(key, new ByteArrayInputStream(content), content.length, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("超过存储上限");
        assertThat(service.exists(key)).isFalse();
    }

    @Test
    @DisplayName("空文件被拒绝")
    void rejectEmpty() {
        assertThatThrownBy(() -> service.store("kb/5/uuid-5/empty.txt",
                new ByteArrayInputStream(new byte[0]), 0, "text/plain"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("文件内容为空");
    }

    @Test
    @DisplayName("历史数据兼容：base-dir 下的绝对路径仍可读取")
    void readLegacyAbsolutePath() throws IOException {
        Path legacy = tempDir.resolve("1").resolve("100_legacy.pdf");
        Files.createDirectories(legacy.getParent());
        Files.writeString(legacy, "legacy-content");

        try (InputStream in = service.openStream(legacy.toString())) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("legacy-content");
        }
        assertThat(service.exists(legacy.toString())).isTrue();
    }
}
