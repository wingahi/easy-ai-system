package com.wgh.rag.kb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.kb.entity.KnowledgeBase;
import com.wgh.rag.kb.entity.KnowledgeFile;
import com.wgh.rag.kb.mapper.KnowledgeBaseMapper;
import com.wgh.rag.kb.mapper.KnowledgeFileMapper;
import com.wgh.rag.kb.mapper.KnowledgeFileVersionMapper;
import com.wgh.rag.kb.parser.DocumentParserDispatcher;
import com.wgh.rag.kb.splitter.TextSplitter;
import com.wgh.rag.llm.entity.EmbeddingModelConfig;
import com.wgh.rag.llm.service.LlmModelService;
import com.wgh.rag.storage.service.FileStorageService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 重建状态机：并发互斥、成功才切指针并清理旧表、失败保留旧数据并清理半成品表。
 */
@DisplayName("知识库 - 向量重建状态机")
class KnowledgeFileRebuildTest {

    private final KnowledgeBaseMapper kbMapper = Mockito.mock(KnowledgeBaseMapper.class);
    private final KnowledgeFileMapper fileMapper = Mockito.mock(KnowledgeFileMapper.class);
    private final LlmModelService llmModelService = Mockito.mock(LlmModelService.class);
    private final VectorStoreService vectorStoreService = Mockito.mock(VectorStoreService.class);
    private final DocumentParserDispatcher documentParser = Mockito.mock(DocumentParserDispatcher.class);
    private final TextSplitter textSplitter = Mockito.mock(TextSplitter.class);
    private final FileStorageService fileStorageService = Mockito.mock(FileStorageService.class);

    private KnowledgeBaseService kbService;
    private KnowledgeFileService fileService;

    @BeforeAll
    static void initMybatisTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, KnowledgeBase.class);
        TableInfoHelper.initTableInfo(assistant, KnowledgeFile.class);
    }

    @BeforeEach
    void setUp() {
        kbService = new KnowledgeBaseService(kbMapper, fileMapper, llmModelService,
                vectorStoreService, Mockito.mock(SemanticCacheService.class));
        fileService = new KnowledgeFileService(fileMapper,
                Mockito.mock(KnowledgeFileVersionMapper.class), kbService, documentParser,
                textSplitter, vectorStoreService, Mockito.mock(SemanticCacheService.class),
                new ObjectMapper(), fileStorageService);
    }

    @Test
    @DisplayName("并发互斥：已有重建任务时拒绝，并清理本次新建的目标表")
    void claim_rejectsConcurrentRebuild() {
        when(kbMapper.selectById(1L)).thenReturn(kb("wgh_kb_old", 7L, "RUNNING", null));
        when(fileMapper.selectCount(any())).thenReturn(1L);
        when(llmModelService.getEmbeddingModel(7L)).thenReturn(model(1536));
        when(kbMapper.update(isNull(), any())).thenReturn(0);

        assertThatThrownBy(() -> kbService.claimRebuild(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已有重建任务");
        verify(vectorStoreService).dropCollection(anyString());
    }

    @Test
    @DisplayName("重建成功：切到新 collection/新模型，并清理旧表")
    void rebuild_success_swapsAndDropsOld() throws Exception {
        KnowledgeBase kb = kb("wgh_kb_old", 7L, "PENDING", 8L);
        when(kbMapper.selectById(1L)).thenReturn(kb);
        when(llmModelService.getEmbeddingModel(8L)).thenReturn(model(768));
        when(fileMapper.selectCount(any())).thenReturn(2L);
        when(kbMapper.update(isNull(), any())).thenReturn(1);
        when(fileMapper.selectList(any())).thenReturn(List.of(file(11L), file(12L)));
        when(fileStorageService.openStream(anyString()))
                .thenReturn(new ByteArrayInputStream("hello".getBytes()));
        when(documentParser.parse(any(File.class), anyString())).thenReturn(List.of("段落"));
        when(textSplitter.splitDetailed(anyList(), any(), any(), any())).thenReturn(List.of());
        when(vectorStoreService.existingContentHashes(anyString())).thenReturn(Set.of());
        // 新表实际行数（重建收尾据此回填 chunk_count，不能用文件数或 rebuild_total）
        when(vectorStoreService.countVectors(anyString())).thenReturn(3);

        KnowledgeBaseService.RebuildPlan plan = kbService.claimRebuild(1L);
        fileService.runRebuild(plan);

        assertThat(plan.targetCollection()).startsWith("wgh_kb_1_");
        verify(kbMapper).updateById((KnowledgeBase) argThat((KnowledgeBase k) -> "SUCCEEDED".equals(k.getRebuildStatus())
                && plan.targetCollection().equals(k.getCollectionName())
                && Long.valueOf(8L).equals(k.getEmbeddingModelId())
                && Integer.valueOf(768).equals(k.getDimension())
                && Integer.valueOf(3).equals(k.getChunkCount())));
        verify(vectorStoreService).dropCollection("wgh_kb_old");
        verify(vectorStoreService, never()).insertVectors(anyString(), anyList(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("重建失败：不切换指针、不删旧表，只清理半成品目标表")
    void rebuild_failure_keepsOldData() throws Exception {
        KnowledgeBase kb = kb("wgh_kb_old", 7L, "PENDING", 8L);
        when(kbMapper.selectById(1L)).thenReturn(kb);
        when(llmModelService.getEmbeddingModel(8L)).thenReturn(model(768));
        when(fileMapper.selectCount(any())).thenReturn(1L);
        when(kbMapper.update(isNull(), any())).thenReturn(1);
        when(fileMapper.selectList(any())).thenReturn(List.of(file(11L)));
        when(fileStorageService.openStream(anyString()))
                .thenReturn(new ByteArrayInputStream("hello".getBytes()));
        when(documentParser.parse(any(File.class), anyString()))
                .thenThrow(new BusinessException("解析失败"));

        KnowledgeBaseService.RebuildPlan plan = kbService.claimRebuild(1L);
        fileService.runRebuild(plan);

        verify(kbMapper).updateById((KnowledgeBase) argThat((KnowledgeBase k) -> "FAILED".equals(k.getRebuildStatus())
                && k.getCollectionName() == null
                && k.getEmbeddingModelId() == null));
        verify(kbMapper, never()).updateById((KnowledgeBase) argThat((KnowledgeBase k) ->
                "SUCCEEDED".equals(k.getRebuildStatus())));
        verify(fileMapper, atLeastOnce()).updateById((KnowledgeFile) argThat((KnowledgeFile f) -> "FAILED".equals(f.getStatus())));
        verify(vectorStoreService).dropCollection(plan.targetCollection());
        verify(vectorStoreService, never()).dropCollection("wgh_kb_old");
        verify(vectorStoreService, never()).insertVectors(anyString(), anyList(), anyLong(), anyInt());
    }

    private static KnowledgeBase kb(String collection, Long modelId, String rebuildStatus, Long pendingModelId) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(1L);
        kb.setName("kb");
        kb.setCollectionName(collection);
        kb.setEmbeddingModelId(modelId);
        kb.setDimension(1536);
        kb.setRebuildStatus(rebuildStatus);
        kb.setPendingEmbeddingModelId(pendingModelId);
        return kb;
    }

    private static EmbeddingModelConfig model(Integer dimension) {
        EmbeddingModelConfig model = new EmbeddingModelConfig();
        model.setDimension(dimension);
        return model;
    }

    private static KnowledgeFile file(Long id) {
        KnowledgeFile file = new KnowledgeFile();
        file.setId(id);
        file.setFileName("f" + id + ".txt");
        file.setFilePath("kb/1/x/f" + id + ".txt");
        file.setFileType("txt");
        file.setSplitStrategy("AUTO");
        return file;
    }
}
