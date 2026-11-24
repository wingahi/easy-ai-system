package com.wgh.rag.kb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.security.LoginUser;
import com.wgh.rag.kb.entity.KnowledgeBase;
import com.wgh.rag.kb.entity.KnowledgeFile;
import com.wgh.rag.kb.entity.UploadSession;
import com.wgh.rag.kb.mapper.UploadSessionMapper;
import com.wgh.rag.storage.service.FileStorageService;
import com.wgh.rag.storage.service.StorageKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 分片上传单测：分片大小推导、序号越界、断点续传进度、合并顺序与哈希、完成后幂等返回
 */
@DisplayName("分片上传与断点续传测试")
class ChunkUploadServiceTest {

    private UploadSessionMapper sessionMapper;
    private KnowledgeBaseService kbService;
    private KnowledgeFileService fileService;
    private FileStorageService fileStorageService;
    private ChunkUploadService service;

    @BeforeEach
    void setUp() {
        sessionMapper = Mockito.mock(UploadSessionMapper.class);
        kbService = Mockito.mock(KnowledgeBaseService.class);
        fileService = Mockito.mock(KnowledgeFileService.class);
        fileStorageService = Mockito.mock(FileStorageService.class);
        service = new ChunkUploadService(sessionMapper, kbService, fileService,
                fileStorageService, new ObjectMapper());
        KnowledgeBase kb = new KnowledgeBase();
        kb.setCreateBy("alice");
        kb.setVisibility("PRIVATE");
        when(kbService.getById(anyLong())).thenReturn(kb);
        loginAs("alice");
    }

    @AfterEach
    void tearDown() {
        LoginUser.clear();
    }

    @Test
    @DisplayName("未指定分片大小时按 5MB 推导分片数")
    void initDefaultsChunkSize() {
        ChunkUploadService.InitResult result = service.init(
                10L, "big.pdf", 12L * 1024 * 1024, null, "PARAGRAPH", null);

        assertThat(result.chunkSize()).isEqualTo(ChunkUploadService.DEFAULT_CHUNK_SIZE);
        assertThat(result.totalChunks()).isEqualTo(3);
        assertThat(result.uploadedChunks()).isEmpty();
    }

    @Test
    @DisplayName("分片大小越界或文件为空时拒绝初始化")
    void initRejectsInvalidParams() {
        assertThatThrownBy(() -> service.init(10L, "a.txt", 0, 5 * 1024 * 1024, null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("文件大小");
        assertThatThrownBy(() -> service.init(10L, "a.txt", 100, 1024, null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("1MB ~ 64MB");
        assertThatThrownBy(() -> service.init(10L, " ", 100, 5 * 1024 * 1024, null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("文件名");
    }

    @Test
    @DisplayName("分片上传按序号落存储并推进进度，重复上传同序号保持幂等")
    void uploadChunkIsIdempotent() throws IOException {
        UploadSession session = uploadingSession("[]");
        when(sessionMapper.selectById(1L)).thenReturn(session);

        ChunkUploadService.UploadChunkResult first = service.uploadChunk(1L, 0, chunk("abc"));
        ChunkUploadService.UploadChunkResult again = service.uploadChunk(1L, 0, chunk("abc"));

        verify(fileStorageService, Mockito.times(2)).store(
                eq(StorageKeys.chunkKey(10L, "uuid-1", 0)), any(InputStream.class), eq(3L), eq(null));
        assertThat(first.uploadedCount()).isEqualTo(1);
        assertThat(again.uploadedCount()).isEqualTo(1);
        assertThat(again.totalChunks()).isEqualTo(2);

        ArgumentCaptor<UploadSession> captor = ArgumentCaptor.forClass(UploadSession.class);
        verify(sessionMapper, Mockito.times(2)).updateById(captor.capture());
        assertThat(captor.getValue().getUploadedChunks()).isEqualTo("[0]");
    }

    @Test
    @DisplayName("分片序号越界时拒绝上传")
    void uploadChunkRejectsOutOfRangeIndex() {
        when(sessionMapper.selectById(1L)).thenReturn(uploadingSession("[]"));

        assertThatThrownBy(() -> service.uploadChunk(1L, 2, chunk("abc")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("分片序号越界");
        verify(fileStorageService, never()).store(anyString(), any(InputStream.class), anyLong(), any());
    }

    @Test
    @DisplayName("分片不完整时拒绝合并并列出缺失序号")
    void completeRejectsMissingChunks() {
        when(sessionMapper.selectById(1L)).thenReturn(uploadingSession("[0]"));

        assertThatThrownBy(() -> service.complete(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("缺少序号：[1]");
        verify(fileService, never()).storeAndRegister(
                anyLong(), anyString(), anyString(), anyLong(), any(), anyString(), any(), anyString(), any());
    }

    @Test
    @DisplayName("合并按序号拼接并计算整文件哈希，完成后标记会话并清理分片")
    void completeMergesChunksInOrder() throws Exception {
        UploadSession session = uploadingSession("[0,1]");
        when(sessionMapper.selectById(1L)).thenReturn(session);
        when(fileStorageService.openStream(StorageKeys.chunkKey(10L, "uuid-1", 0)))
                .thenAnswer(inv -> new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8)));
        when(fileStorageService.openStream(StorageKeys.chunkKey(10L, "uuid-1", 1)))
                .thenAnswer(inv -> new ByteArrayInputStream("def".getBytes(StandardCharsets.UTF_8)));

        KnowledgeFile file = new KnowledgeFile();
        file.setId(99L);
        when(fileService.storeAndRegister(anyLong(), anyString(), anyString(), anyLong(), any(),
                anyString(), any(), anyString(), any()))
                .thenAnswer(inv -> {
                    KnowledgeFileService.ContentSource source = inv.getArgument(6);
                    try (InputStream in = source.open()) {
                        assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("abcdef");
                    }
                    return new KnowledgeFileService.IngestOutcome(file, false);
                });

        KnowledgeFile result = service.complete(1L);

        assertThat(result.getId()).isEqualTo(99L);
        verify(fileService).storeAndRegister(eq(10L), eq("big.pdf"), eq("PDF"), eq(6L), eq(null),
                eq(sha256("abcdef")), any(), eq("PARAGRAPH"), eq(null));
        assertThat(session.getStatus()).isEqualTo("COMPLETED");
        assertThat(session.getFileId()).isEqualTo(99L);
        verify(fileStorageService).delete(StorageKeys.chunkKey(10L, "uuid-1", 0));
        verify(fileStorageService).delete(StorageKeys.chunkKey(10L, "uuid-1", 1));
    }

    @Test
    @DisplayName("会话已完成时返回既有文件，不再重复合并")
    void completeIsIdempotentAfterFinished() {
        UploadSession session = uploadingSession("[0,1]");
        session.setStatus("COMPLETED");
        session.setFileId(99L);
        when(sessionMapper.selectById(1L)).thenReturn(session);
        KnowledgeFile file = new KnowledgeFile();
        file.setId(99L);
        when(fileService.getById(99L)).thenReturn(file);

        assertThat(service.complete(1L).getId()).isEqualTo(99L);
        verify(fileService, never()).storeAndRegister(
                anyLong(), anyString(), anyString(), anyLong(), any(), anyString(), any(), anyString(), any());
    }

    @Test
    @DisplayName("进度查询返回已完成分片，供前端断点续传")
    void progressReturnsUploadedChunks() {
        when(sessionMapper.selectById(1L)).thenReturn(uploadingSession("[0]"));

        ChunkUploadService.Progress progress = service.progress(1L);

        assertThat(progress.uploadedChunks()).containsExactly(0);
        assertThat(progress.totalChunks()).isEqualTo(2);
        assertThat(progress.status()).isEqualTo("UPLOADING");
    }

    @Test
    @DisplayName("他人会话不可上传分片、不可合并，按不存在语义返回")
    void otherUserCannotOperateSession() throws IOException {
        when(sessionMapper.selectById(1L)).thenReturn(uploadingSession("[]"));
        loginAs("bob");

        assertThatThrownBy(() -> service.uploadChunk(1L, 0, chunk("abc")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("上传会话不存在");
        assertThatThrownBy(() -> service.complete(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("上传会话不存在");
        assertThatThrownBy(() -> service.progress(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("上传会话不存在");
        verify(fileStorageService, never()).store(anyString(), any(InputStream.class), anyLong(), any());
        verify(fileService, never()).storeAndRegister(
                anyLong(), anyString(), anyString(), anyLong(), any(), anyString(), any(), anyString(), any());
    }

    @Test
    @DisplayName("不存在的会话与他人会话返回相同的错误语义")
    void missingSessionHasSameSemantics() {
        when(sessionMapper.selectById(1L)).thenReturn(null);
        String missing = catchMessage(() -> service.progress(1L));

        when(sessionMapper.selectById(2L)).thenReturn(uploadingSession("[]"));
        loginAs("bob");
        String foreign = catchMessage(() -> service.progress(2L));

        assertThat(missing).isEqualTo(foreign).isEqualTo("上传会话不存在");
    }

    @Test
    @DisplayName("ADMIN 可操作他人会话")
    void adminCanOperateOtherSession() {
        when(sessionMapper.selectById(1L)).thenReturn(uploadingSession("[0]"));
        LoginUser admin = new LoginUser();
        admin.setUsername("admin");
        admin.setRoles(Set.of("ADMIN"));
        LoginUser.set(admin);

        ChunkUploadService.Progress progress = service.progress(1L);

        assertThat(progress.uploadedChunks()).containsExactly(0);
    }

    @Test
    @DisplayName("无登录上下文时拒绝访问会话")
    void anonymousIsRejected() {
        LoginUser.clear();
        when(sessionMapper.selectById(1L)).thenReturn(uploadingSession("[]"));

        assertThatThrownBy(() -> service.progress(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("未授权访问");
    }

    private void loginAs(String username) {
        LoginUser user = new LoginUser();
        user.setUsername(username);
        user.setRoles(Set.of());
        LoginUser.set(user);
    }

    private String catchMessage(Runnable action) {
        try {
            action.run();
            return null;
        } catch (BusinessException e) {
            return e.getMessage();
        }
    }

    private UploadSession uploadingSession(String uploaded) {
        UploadSession session = new UploadSession();
        session.setId(1L);
        session.setKnowledgeBaseId(10L);
        session.setUploadUuid("uuid-1");
        session.setFileName("big.pdf");
        session.setFileType("PDF");
        session.setFileSize(6L);
        session.setChunkSize(3);
        session.setTotalChunks(2);
        session.setUploadedChunks(uploaded);
        session.setStatus("UPLOADING");
        session.setSplitStrategy("PARAGRAPH");
        session.setCreateBy("alice");
        return session;
    }

    private MultipartFile chunk(String content) throws IOException {
        MultipartFile file = Mockito.mock(MultipartFile.class);
        when(file.getInputStream()).thenAnswer(inv ->
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
        when(file.getSize()).thenReturn((long) content.length());
        when(file.isEmpty()).thenReturn(false);
        return file;
    }

    private String sha256(String text) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}
