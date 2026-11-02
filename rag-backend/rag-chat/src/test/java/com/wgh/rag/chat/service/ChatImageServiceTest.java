package com.wgh.rag.chat.service;

import com.wgh.rag.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;

/**
 * ChatImageService 单测：图片上传校验、data URL 生成、imageUrls 入参校验
 */
class ChatImageServiceTest {

    private ChatImageService service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        service = new ChatImageService();
        ReflectionTestUtils.setField(service, "uploadDir", tempDir.toString());
    }

    @Test
    void store_saves_file_and_returns_data_url() throws Exception {
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};
        MockMultipartFile file = new MockMultipartFile("file", "a.png", "image/png", png);

        String dataUrl = service.store(file);

        assertThat(dataUrl).startsWith("data:image/png;base64,");
        assertThat(Base64.getDecoder().decode(dataUrl.substring("data:image/png;base64,".length())))
                .isEqualTo(png);
        // 落盘到 chat-images 子目录
        try (Stream<Path> files = Files.list(tempDir.resolve("chat-images"))) {
            assertThat(files).hasSize(1);
        }
    }

    @Test
    void store_rejects_empty_file() {
        MockMultipartFile file = new MockMultipartFile("file", "a.png", "image/png", new byte[0]);
        assertThatThrownBy(() -> service.store(file))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能为空");
    }

    @Test
    void store_rejects_non_image_type() {
        MockMultipartFile file = new MockMultipartFile("file", "a.txt", "text/plain", "hi".getBytes());
        assertThatThrownBy(() -> service.store(file))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("仅支持");
    }

    @Test
    void store_rejects_oversize() {
        byte[] big = new byte[11 * 1024 * 1024];
        MockMultipartFile file = new MockMultipartFile("file", "big.jpg", "image/jpeg", big);
        assertThatThrownBy(() -> service.store(file))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("10MB");
    }

    @Test
    void validate_accepts_data_url_and_http() {
        assertThatCode(() -> service.validateImageUrls(List.of(
                "data:image/png;base64,iVBORw0KGgo=",
                "https://example.com/a.jpg",
                "http://example.com/b.png"))).doesNotThrowAnyException();
    }

    @Test
    void validate_accepts_null_and_empty() {
        assertThatCode(() -> service.validateImageUrls(null)).doesNotThrowAnyException();
        assertThatCode(() -> service.validateImageUrls(List.of())).doesNotThrowAnyException();
    }

    @Test
    void validate_rejects_too_many_images() {
        List<String> six = java.util.stream.IntStream.range(0, 6)
                .mapToObj(i -> "https://example.com/" + i + ".jpg")
                .toList();
        assertThatThrownBy(() -> service.validateImageUrls(six))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("最多");
    }

    @Test
    void validate_rejects_bad_scheme() {
        assertThatThrownBy(() -> service.validateImageUrls(List.of("file:///etc/passwd")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("data:image/");
    }

    @Test
    void validate_rejects_duplicate() {
        List<String> dup = List.of("https://example.com/a.jpg", "https://example.com/a.jpg");
        assertThatThrownBy(() -> service.validateImageUrls(dup))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("重复");
    }
}
