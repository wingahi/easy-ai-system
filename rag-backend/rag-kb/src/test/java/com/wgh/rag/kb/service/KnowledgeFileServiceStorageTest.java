package com.wgh.rag.kb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.result.ResultCode;
import com.wgh.rag.kb.entity.KnowledgeBase;
import com.wgh.rag.kb.entity.KnowledgeFile;
import com.wgh.rag.kb.mapper.KnowledgeFileMapper;
import com.wgh.rag.kb.mapper.KnowledgeFileVersionMapper;
import com.wgh.rag.kb.parser.DocumentParserDispatcher;
import com.wgh.rag.kb.parser.ParsedDocument;
import com.wgh.rag.kb.splitter.ChunkOutput;
import com.wgh.rag.kb.splitter.TextSplitter;
import com.wgh.rag.storage.service.FileStorageService;
import com.wgh.rag.storage.service.StoredObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 存储抽象与 KnowledgeFileService 的衔接：上传写入 key、预览读取、删除清理。
 * 用于确保 rag-storage 抽取后上传/预览/删除链路行为不变。
 */
class KnowledgeFileServiceStorageTest {

    private final KnowledgeFileMapper fileMapper = Mockito.mock(KnowledgeFileMapper.class);
    private final KnowledgeFileVersionMapper versionMapper = Mockito.mock(KnowledgeFileVersionMapper.class);
    private final KnowledgeBaseService kbService = Mockito.mock(KnowledgeBaseService.class);
    private final DocumentParserDispatcher documentParser = Mockito.mock(DocumentParserDispatcher.class);
    private final TextSplitter textSplitter = Mockito.mock(TextSplitter.class);
    private final VectorStoreService vectorStoreService = Mockito.mock(VectorStoreService.class);
    private final FileStorageService fileStorageService = Mockito.mock(FileStorageService.class);

    private KnowledgeFileService service;

    @BeforeEach
    void setUp() {
        service = new KnowledgeFileService(
                fileMapper,
                versionMapper,
                kbService,
                documentParser,
                textSplitter,
                vectorStoreService,
                Mockito.mock(SemanticCacheService.class),
                new ObjectMapper(),
                fileStorageService);
        when(kbService.getById(anyLong())).thenReturn(kb(1L));
    }

    @Test
    @DisplayName("上传：按 kb/{kbId}/{uuid}/{fileName} 写入存储并落库该 key")
    void upload_storesWithStandardKey() {
        when(fileStorageService.store(anyString(), any(InputStream.class), anyLong(), any()))
                .thenAnswer(inv -> new StoredObject(inv.getArgument(0), 5L, "text/plain"));

        KnowledgeFile file = service.upload(1L,
                new MockMultipartFile("file", "a.txt", "text/plain", "hello".getBytes()), "AUTO", null);

        assertThat(file.getFilePath()).matches("kb/1/[0-9a-fA-F-]{36}/a.txt");
        verify(fileMapper).insert(file);
    }

    @Test
    @DisplayName("上传：落库失败时清理已写入的存储对象")
    void upload_insertFailure_deletesStoredObject() {
        when(fileStorageService.store(anyString(), any(InputStream.class), anyLong(), any()))
                .thenAnswer(inv -> new StoredObject(inv.getArgument(0), 5L, "text/plain"));
        when(fileMapper.insert(any(KnowledgeFile.class))).thenThrow(new RuntimeException("db down"));

        assertThatThrownBy(() -> service.upload(1L,
                new MockMultipartFile("file", "a.txt", "text/plain", "hello".getBytes()), "AUTO", null))
                .isInstanceOf(RuntimeException.class);

        verify(fileStorageService).delete(Mockito.startsWith("kb/1/"));
    }

    @Test
    @DisplayName("预览：通过存储 openStream 读取对象，不感知本地路径")
    void previewSplit_readsThroughStorage() throws Exception {
        KnowledgeFile file = file(2L, kb(1L), "kb/1/u/a.txt", "txt");
        when(fileMapper.selectById(2L)).thenReturn(file);
        when(fileStorageService.openStream("kb/1/u/a.txt"))
                .thenReturn(new ByteArrayInputStream("hello".getBytes()));
        when(documentParser.parseDocument(any(java.io.File.class), eq("txt"))).thenReturn(
                new ParsedDocument(List.of("hello"), null, null, null, "UTF-8", 5));
        when(textSplitter.splitDetailed(anyList(), any(), any(), any()))
                .thenReturn(List.of(ChunkOutput.of("hello")));

        Map<String, Object> result = service.previewSplit(2L);

        assertThat(result).containsEntry("fileName", "a.txt").containsEntry("chunkCount", 1);
        verify(fileStorageService).openStream("kb/1/u/a.txt");
    }

    @Test
    @DisplayName("删除：逻辑删除后清理存储对象与向量")
    void delete_cleansStorageAndVectors() {
        KnowledgeFile file = file(3L, kb(1L), "kb/1/u/a.txt", "txt");
        when(fileMapper.selectById(3L)).thenReturn(file);

        service.delete(3L);

        verify(vectorStoreService).deleteByFileId("kb_collection", 3L);
        verify(fileMapper).deleteById(3L);
        verify(fileStorageService).delete("kb/1/u/a.txt");
        verify(kbService).updateCounts(1L);
    }

    @Test
    @DisplayName("回滚：存储对象缺失时预览抛明确业务异常，不出现空指针")
    void preview_missingObject_failsFast() {
        KnowledgeFile file = file(2L, kb(1L), "kb/1/u/a.txt", "txt");
        when(fileMapper.selectById(2L)).thenReturn(file);
        when(fileStorageService.openStream(anyString())).thenThrow(
                new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "存储对象不存在: kb/1/u/a.txt"));

        assertThatThrownBy(() -> service.previewSplit(2L))
                .isInstanceOf(BusinessException.class);
        verify(documentParser, never()).parseDocument(any(java.io.File.class), anyString());
    }

    private static KnowledgeBase kb(Long id) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(id);
        kb.setCollectionName("kb_collection");
        kb.setEmbeddingModelId(1L);
        return kb;
    }

    private static KnowledgeFile file(Long id, KnowledgeBase kb, String path, String type) {
        KnowledgeFile file = new KnowledgeFile();
        file.setId(id);
        file.setKnowledgeBaseId(kb.getId());
        file.setFileName(path.substring(path.lastIndexOf('/') + 1));
        file.setFilePath(path);
        file.setFileType(type);
        file.setSplitStrategy("AUTO");
        return file;
    }
}
