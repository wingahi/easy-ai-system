package com.wgh.rag.kb.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.wgh.rag.kb.entity.UploadSession;
import com.wgh.rag.kb.mapper.UploadSessionMapper;
import com.wgh.rag.storage.service.FileStorageService;
import com.wgh.rag.storage.service.StorageKeys;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 过期上传会话清理单测：只选 UPLOADING 超时会话、分片对象随会话删除、无过期时不动作
 */
@DisplayName("过期分片上传会话清理测试")
class UploadSessionCleanerTest {

    private UploadSessionMapper sessionMapper;
    private FileStorageService fileStorageService;
    private UploadSessionCleaner cleaner;

    @BeforeEach
    void setUp() {
        sessionMapper = Mockito.mock(UploadSessionMapper.class);
        fileStorageService = Mockito.mock(FileStorageService.class);
        cleaner = new UploadSessionCleaner(sessionMapper, fileStorageService, new UploadCleanupProperties());
    }

    @Test
    @DisplayName("到期未完成会话被清理，并删除其全部分片对象")
    void cleansExpiredSessionAndChunks() {
        UploadSession stale = session(7L, "uuid-7", 2);
        when(sessionMapper.selectList(any())).thenReturn(List.of(stale));

        int cleaned = cleaner.cleanupExpired();

        assertThat(cleaned).isEqualTo(1);
        verify(fileStorageService).delete(StorageKeys.chunkKey(10L, "uuid-7", 0));
        verify(fileStorageService).delete(StorageKeys.chunkKey(10L, "uuid-7", 1));
        verify(sessionMapper).deleteById(7L);
    }

    @Test
    @DisplayName("没有过期会话时不删除任何对象")
    void keepsUnexpiredSessions() {
        when(sessionMapper.selectList(any())).thenReturn(List.of());

        assertThat(cleaner.cleanupExpired()).isZero();

        verify(fileStorageService, never()).delete(anyString());
        verify(sessionMapper, never()).deleteById(anyLong());
    }

    @BeforeAll
    static void initTableInfo() {
        // 让 Lambda 条件能在纯单测中渲染为 SQL 片段（无 Spring 容器时 MyBatis-Plus 不注册元数据）
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), UploadSession.class);
    }

    @Test
    @DisplayName("查询条件限定 UPLOADING 且按更新时间判断过期，已完成会话不会被选中")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void queryTargetsOnlyStaleUploadingSessions() {
        when(sessionMapper.selectList(any())).thenReturn(List.of());

        cleaner.cleanupExpired();

        ArgumentCaptor<LambdaQueryWrapper<UploadSession>> captor =
                ArgumentCaptor.forClass((Class) LambdaQueryWrapper.class);
        verify(sessionMapper).selectList(captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertThat(sql).contains("status").contains("update_time").containsIgnoringCase("limit");
    }

    @Test
    @DisplayName("单个会话清理失败不影响其他会话")
    void continuesWhenOneSessionFails() {
        UploadSession first = session(7L, "uuid-7", 1);
        UploadSession second = session(8L, "uuid-8", 1);
        when(sessionMapper.selectList(any())).thenReturn(List.of(first, second));
        when(sessionMapper.deleteById(7L)).thenThrow(new IllegalStateException("db error"));

        int cleaned = cleaner.cleanupExpired();

        assertThat(cleaned).isEqualTo(1);
        verify(sessionMapper).deleteById(8L);
    }

    private UploadSession session(Long id, String uuid, int totalChunks) {
        UploadSession session = new UploadSession();
        session.setId(id);
        session.setKnowledgeBaseId(10L);
        session.setUploadUuid(uuid);
        session.setTotalChunks(totalChunks);
        session.setStatus("UPLOADING");
        return session;
    }
}
