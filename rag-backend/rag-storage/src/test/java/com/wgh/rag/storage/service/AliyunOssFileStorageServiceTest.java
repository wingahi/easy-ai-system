package com.wgh.rag.storage.service;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PutObjectResult;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.storage.properties.StorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OSS 实现单测：用 Mock OSS 客户端断言调用参数、重试与清理。
 * <p>
 * 未使用真实 OSS 凭证，因此只验证实现逻辑，不代表真实 OSS 端到端可用。
 */
class AliyunOssFileStorageServiceTest {

    private static final String BUCKET = "wgh-test";
    private static final String ENDPOINT = "https://oss-cn-hangzhou.aliyuncs.com";

    private StorageProperties properties;
    private OSS client;
    private AliyunOssFileStorageService service;

    @BeforeEach
    void setUp() {
        properties = new StorageProperties();
        properties.setType("oss");
        properties.getOss().setEndpoint(ENDPOINT);
        properties.getOss().setBucket(BUCKET);
        properties.getOss().setAccessKeyId("ak");
        properties.getOss().setAccessKeySecret("sk");
        properties.getOss().setKeyPrefix("rag/");
        properties.getOss().setMaxRetries(3);

        client = mock(OSS.class);
        service = new AliyunOssFileStorageService(properties, client);
        service.init();
    }

    @Test
    @DisplayName("上传：流式写入带前缀的对象名，并按实际大小设置 Content-Length")
    void storeCallsPutObjectWithExpectedArgs() {
        when(client.putObject(anyString(), anyString(), any(InputStream.class), any(ObjectMetadata.class)))
                .thenReturn(new PutObjectResult());
        byte[] content = "hello oss".getBytes(StandardCharsets.UTF_8);

        StoredObject stored = service.store("kb/1/uuid/制度.pdf", new ByteArrayInputStream(content),
                content.length, "application/pdf");

        ArgumentCaptor<String> bucketCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<ObjectMetadata> metadataCaptor = ArgumentCaptor.forClass(ObjectMetadata.class);
        verify(client).putObject(bucketCaptor.capture(), keyCaptor.capture(),
                any(InputStream.class), metadataCaptor.capture());

        assertThat(bucketCaptor.getValue()).isEqualTo(BUCKET);
        assertThat(keyCaptor.getValue()).isEqualTo("rag/kb/1/uuid/制度.pdf");
        assertThat(metadataCaptor.getValue().getContentLength()).isEqualTo(content.length);
        assertThat(metadataCaptor.getValue().getContentType()).isEqualTo("application/pdf");
        assertThat(stored.key()).isEqualTo("kb/1/uuid/制度.pdf");
        assertThat(stored.size()).isEqualTo(content.length);
        verify(client, Mockito.never()).deleteObject(anyString(), anyString());
    }

    @Test
    @DisplayName("上传：前两次失败后重试成功（每次重新打开流）")
    void storeRetriesUntilSuccess() {
        when(client.putObject(anyString(), anyString(), any(InputStream.class), any(ObjectMetadata.class)))
                .thenThrow(new RuntimeException("network"))
                .thenThrow(new RuntimeException("network"))
                .thenReturn(new PutObjectResult());
        byte[] content = "retry".getBytes(StandardCharsets.UTF_8);

        StoredObject stored = service.store("kb/1/uuid/a.txt", new ByteArrayInputStream(content),
                content.length, "text/plain");

        assertThat(stored.size()).isEqualTo(content.length);
        verify(client, times(3)).putObject(anyString(), anyString(), any(InputStream.class), any(ObjectMetadata.class));
    }

    @Test
    @DisplayName("上传：全部重试失败抛出业务异常并清理残留对象")
    void storeCleansUpWhenAllAttemptsFail() {
        when(client.putObject(anyString(), anyString(), any(InputStream.class), any(ObjectMetadata.class)))
                .thenThrow(new RuntimeException("network"));
        byte[] content = "x".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> service.store("kb/1/uuid/a.txt", new ByteArrayInputStream(content),
                content.length, "text/plain"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("OSS 上传失败");

        verify(client, atLeastOnce()).deleteObject(eq(BUCKET), eq("rag/kb/1/uuid/a.txt"));
    }

    @Test
    @DisplayName("读取：返回对象内容流")
    void openStream() throws Exception {
        OSSObject object = mock(OSSObject.class);
        when(object.getObjectContent()).thenReturn(
                new ByteArrayInputStream("oss-content".getBytes(StandardCharsets.UTF_8)));
        when(client.getObject(BUCKET, "rag/kb/1/uuid/a.txt")).thenReturn(object);

        try (InputStream in = service.openStream("kb/1/uuid/a.txt")) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("oss-content");
        }
    }

    @Test
    @DisplayName("删除与存在性判断使用带前缀的对象名")
    void deleteAndExists() {
        service.delete("kb/1/uuid/a.txt");
        verify(client).deleteObject(BUCKET, "rag/kb/1/uuid/a.txt");

        when(client.doesObjectExist(BUCKET, "rag/kb/1/uuid/b.txt")).thenReturn(true);
        assertThat(service.exists("kb/1/uuid/b.txt")).isTrue();
    }

    @Test
    @DisplayName("签名 URL：按有效期生成，公网直链模式拼接 bucket 域名")
    void resolveUrl() throws Exception {
        when(client.generatePresignedUrl(eq(BUCKET), eq("rag/kb/1/uuid/a.txt"), any(Date.class)))
                .thenReturn(new URL("https://wgh-test.oss-cn-hangzhou.aliyuncs.com/rag/kb/1/uuid/a.txt?Signature=x"));
        assertThat(service.resolveUrl("kb/1/uuid/a.txt"))
                .startsWith("https://wgh-test.oss-cn-hangzhou.aliyuncs.com/");

        properties.getOss().setPublicRead(true);
        assertThat(service.resolveUrl("kb/1/uuid/a.txt"))
                .isEqualTo("https://wgh-test.oss-cn-hangzhou.aliyuncs.com/rag/kb/1/uuid/a.txt");
    }

    @Test
    @DisplayName("配置为空：应用可初始化，但存储操作返回明确错误")
    void emptyConfigFailsOnOperationOnly() {
        StorageProperties empty = new StorageProperties();
        empty.setType("oss");
        AliyunOssFileStorageService notConfigured = new AliyunOssFileStorageService(empty, client);
        notConfigured.init();

        assertThatThrownBy(() -> notConfigured.store("kb/1/uuid/a.txt",
                new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)), 1, "text/plain"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("OSS 存储未配置完整");
        verify(client, Mockito.never()).putObject(anyString(), anyString(), any(InputStream.class), any());
    }
}
