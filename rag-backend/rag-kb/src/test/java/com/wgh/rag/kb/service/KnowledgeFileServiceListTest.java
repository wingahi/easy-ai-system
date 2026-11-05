package com.wgh.rag.kb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.result.ResultCode;
import com.wgh.rag.kb.entity.KnowledgeBase;
import com.wgh.rag.kb.entity.KnowledgeFile;
import com.wgh.rag.kb.mapper.KnowledgeBaseMapper;
import com.wgh.rag.kb.mapper.KnowledgeFileMapper;
import com.wgh.rag.kb.mapper.KnowledgeFileVersionMapper;
import com.wgh.rag.kb.parser.DocumentParserDispatcher;
import com.wgh.rag.kb.splitter.TextSplitter;
import com.wgh.rag.llm.service.LlmModelService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * KnowledgeFileService 列表单测：正常路径 + kbId 不存在路径
 */
class KnowledgeFileServiceListTest {

    private final KnowledgeFileMapper fileMapper = Mockito.mock(KnowledgeFileMapper.class);
    private final KnowledgeBaseMapper kbMapper = Mockito.mock(KnowledgeBaseMapper.class);

    private KnowledgeFileService service;

    @BeforeEach
    void setUp() {
        KnowledgeBaseService kbService = new KnowledgeBaseService(
                kbMapper,
                fileMapper,
                Mockito.mock(LlmModelService.class),
                Mockito.mock(VectorStoreService.class),
                Mockito.mock(SemanticCacheService.class),
                Mockito.mock(org.springframework.context.ApplicationEventPublisher.class));
        service = new KnowledgeFileService(
                fileMapper,
                Mockito.mock(KnowledgeFileVersionMapper.class),
                kbService,
                Mockito.mock(DocumentParserDispatcher.class),
                Mockito.mock(TextSplitter.class),
                Mockito.mock(VectorStoreService.class),
                Mockito.mock(SemanticCacheService.class),
                new ObjectMapper(),
                Mockito.mock(com.wgh.rag.storage.service.FileStorageService.class));
    }

    @Test
    @DisplayName("kb 存在：返回该知识库文件列表")
    void listByKbId_existingKb() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(1L);
        when(kbMapper.selectById(1L)).thenReturn(kb);
        KnowledgeFile f1 = file(1L, 1L, "a.pdf");
        KnowledgeFile f2 = file(2L, 1L, "b.pdf");
        when(fileMapper.selectList(any())).thenReturn(List.of(f1, f2));

        List<KnowledgeFile> files = service.listByKbId(1L);

        assertThat(files).extracting(KnowledgeFile::getFileName).containsExactly("a.pdf", "b.pdf");
    }

    @Test
    @DisplayName("kb 不存在：抛出 3001 业务异常，且不再查询文件表")
    void listByKbId_missingKb() {
        when(kbMapper.selectById(99999L)).thenReturn(null);

        assertThatThrownBy(() -> service.listByKbId(99999L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(ResultCode.RESOURCE_NOT_FOUND.getCode()))
                .hasMessage("知识库不存在");

        verify(fileMapper, never()).selectList(any());
    }

    private static KnowledgeFile file(Long id, Long kbId, String name) {
        KnowledgeFile file = new KnowledgeFile();
        file.setId(id);
        file.setKnowledgeBaseId(kbId);
        file.setFileName(name);
        return file;
    }
}
