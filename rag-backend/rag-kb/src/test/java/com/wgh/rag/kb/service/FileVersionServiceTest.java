package com.wgh.rag.kb.service;

import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.result.ResultCode;
import com.wgh.rag.kb.entity.KnowledgeFile;
import com.wgh.rag.kb.entity.KnowledgeFileVersion;
import com.wgh.rag.kb.mapper.KnowledgeFileVersionMapper;
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
 * 文件版本列表：文件不存在时统一返回 3001
 */
class FileVersionServiceTest {

    private final KnowledgeFileVersionMapper versionMapper = Mockito.mock(KnowledgeFileVersionMapper.class);
    private final KnowledgeFileService fileService = Mockito.mock(KnowledgeFileService.class);

    private FileVersionService service;

    @BeforeEach
    void setUp() {
        service = new FileVersionService(versionMapper, fileService);
    }

    @Test
    @DisplayName("file 存在：返回版本列表")
    void list_existingFile() {
        when(fileService.getById(2L)).thenReturn(new KnowledgeFile());
        KnowledgeFileVersion v1 = new KnowledgeFileVersion();
        v1.setFileId(2L);
        v1.setVersion(1);
        when(versionMapper.selectList(any())).thenReturn(List.of(v1));

        List<KnowledgeFileVersion> versions = service.list(2L);

        assertThat(versions).extracting(KnowledgeFileVersion::getVersion).containsExactly(1);
    }

    @Test
    @DisplayName("file 不存在：抛 3001 且不查询版本表")
    void list_missingFile() {
        when(fileService.getById(999999L)).thenThrow(new BusinessException(
                ResultCode.RESOURCE_NOT_FOUND, "文件不存在"));

        assertThatThrownBy(() -> service.list(999999L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(ResultCode.RESOURCE_NOT_FOUND.getCode()))
                .hasMessage("文件不存在");

        verify(versionMapper, never()).selectList(any());
    }
}
