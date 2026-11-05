package com.wgh.rag.kb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.kb.entity.KnowledgeBase;
import com.wgh.rag.kb.entity.KnowledgeFile;
import com.wgh.rag.kb.mapper.KnowledgeFileMapper;
import com.wgh.rag.kb.mapper.KnowledgeFileVersionMapper;
import com.wgh.rag.kb.parser.DocumentParserDispatcher;
import com.wgh.rag.kb.splitter.TextSplitter;
import com.wgh.rag.storage.service.FileStorageService;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockMultipartFile;

import java.io.InputStream;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * content_hash 去重：同一知识库内相同文件不重复入库，相同 chunk 不重复嵌入。
 */
@DisplayName("知识库文件 - 内容哈希去重")
class KnowledgeFileContentDedupeTest {

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
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(1L);
        kb.setCollectionName("kb_collection");
        kb.setEmbeddingModelId(1L);
        when(kbService.getById(anyLong())).thenReturn(kb);
    }

    @Test
    @DisplayName("上传：同知识库已有相同内容文件时不落存储、不新增记录")
    void upload_sameContent_returnsExistingFile() {
        KnowledgeFile existing = new KnowledgeFile();
        existing.setId(9L);
        existing.setFileName("a.txt");
        when(fileMapper.selectList(any())).thenReturn(List.of(existing));

        KnowledgeFile result = service.upload(1L,
                new MockMultipartFile("file", "b.txt", "text/plain", "same-content".getBytes()),
                "AUTO", null);

        assertThat(result).isSameAs(existing);
        verify(fileStorageService, never()).store(anyString(), any(InputStream.class), anyLong(), any());
        verify(fileMapper, never()).insert(any(KnowledgeFile.class));
    }

    @Test
    @DisplayName("chunk 去重：批内重复与知识库已存在的文本都不重复嵌入")
    void dedupeSegments_removesDuplicates() {
        TextSegment first = TextSegment.from("重复段落");
        TextSegment second = TextSegment.from("重复段落");
        TextSegment alreadyStored = TextSegment.from("已存在段落");
        Set<String> existing = Set.of(KnowledgeFileService.sha256Hex("已存在段落"));

        List<TextSegment> unique = service.dedupeSegments(
                List.of(first, second, alreadyStored), existing);

        assertThat(unique).containsExactly(first);
    }
}
