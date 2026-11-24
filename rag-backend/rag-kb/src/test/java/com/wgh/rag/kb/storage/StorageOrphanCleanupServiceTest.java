package com.wgh.rag.kb.storage;

import com.wgh.rag.common.metrics.RagMetrics;
import com.wgh.rag.data.tenant.TenantTransactionTemplate;
import com.wgh.rag.kb.entity.KnowledgeFile;
import com.wgh.rag.kb.entity.UploadSession;
import com.wgh.rag.kb.mapper.KnowledgeFileMapper;
import com.wgh.rag.kb.mapper.UploadSessionMapper;
import com.wgh.rag.storage.properties.StorageProperties;
import com.wgh.rag.storage.service.FileStorageService;
import com.wgh.rag.storage.service.StorageInventoryService;
import com.wgh.rag.storage.service.StorageObjectInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 孤儿对象扫描：引用集合口径、默认只报告、删除开关与保护期。
 */
class StorageOrphanCleanupServiceTest {

    private FileStorageService storage;
    private StorageInventoryService inventory;
    private KnowledgeFileMapper fileMapper;
    private UploadSessionMapper sessionMapper;
    private RagMetrics metrics;
    private StorageOrphanCleanupProperties properties;
    private TenantTransactionTemplate transactions;
    private StorageOrphanCleanupService service;

    @BeforeEach
    void setUp() {
        storage = mock(FileStorageService.class);
        inventory = mock(StorageInventoryService.class);
        fileMapper = mock(KnowledgeFileMapper.class);
        sessionMapper = mock(UploadSessionMapper.class);
        metrics = mock(RagMetrics.class);
        properties = new StorageOrphanCleanupProperties();
        transactions = mock(TenantTransactionTemplate.class);
        when(transactions.execute(anyString(), eq(true), any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(2)).get());
        service = new StorageOrphanCleanupService(storage, inventory, fileMapper, sessionMapper,
                metrics, new StorageProperties(), properties, transactions);

        when(inventory.canonicalKey(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("默认只报告：知识库文件与未完成分片不被当作孤儿，且不调用删除")
    void reportsWithoutDeletingByDefault() {
        long old = System.currentTimeMillis() - 120 * 60_000L;
        when(inventory.listByPrefix(anyString())).thenReturn(List.of(
                new StorageObjectInfo("kb/1/u1/keep.txt", 10, old),
                new StorageObjectInfo("kb/1/u2/orphan.txt", 20, old),
                new StorageObjectInfo("kb/1/s1/parts/0.part", 5, old),
                new StorageObjectInfo("kb/1/s1/parts/1.part", 5, old)));
        when(fileMapper.selectList(any())).thenReturn(List.of(file(1L, "keep.txt", "kb/1/u1/keep.txt")));
        when(sessionMapper.selectList(any())).thenReturn(List.of(uploadingSession()));

        StorageOrphanReport report = service.scan(false);

        assertThat(report.scanned()).isEqualTo(4);
        assertThat(report.orphans()).isEqualTo(1);
        assertThat(report.sampleOrphans()).containsExactly("kb/1/u2/orphan.txt");
        assertThat(report.deleted()).isZero();
        verify(storage, never()).delete(anyString());
        verify(metrics).recordStorageOrphans("detected", 1);
    }

    @Test
    @DisplayName("delete=true 仅删除已过保护期的孤儿对象")
    void deletesOnlyAgedOrphansWhenEnabled() {
        long aged = System.currentTimeMillis() - 120 * 60_000L;
        long fresh = System.currentTimeMillis();
        when(inventory.listByPrefix(anyString())).thenReturn(List.of(
                new StorageObjectInfo("kb/1/orphan-old.txt", 10, aged),
                new StorageObjectInfo("kb/1/orphan-young.txt", 10, fresh)));
        when(fileMapper.selectList(any())).thenReturn(List.of());
        when(sessionMapper.selectList(any())).thenReturn(List.of());

        StorageOrphanReport report = service.scan(true);

        assertThat(report.orphans()).isEqualTo(2);
        assertThat(report.deleted()).isEqualTo(1);
        assertThat(report.skippedYoung()).isEqualTo(1);
        assertThat(report.deleteFailed()).isZero();
        verify(storage).delete("kb/1/orphan-old.txt");
        verify(storage, never()).delete("kb/1/orphan-young.txt");
    }

    @Test
    @DisplayName("知识文件记录存在但存储对象缺失：计数并给出可定位明细")
    void reportsMissingKnowledgeFileObjects() {
        when(inventory.listByPrefix(anyString())).thenReturn(List.of());
        when(fileMapper.selectList(any()))
                .thenReturn(List.of(file(9L, "lost.txt", "kb/1/lost.txt")));
        when(sessionMapper.selectList(any())).thenReturn(List.of());

        StorageOrphanReport report = service.scan(false);

        assertThat(report.missingReferences()).isEqualTo(1);
        assertThat(report.sampleMissingReferences()).hasSize(1);
        StorageOrphanReport.MissingReference detail = report.sampleMissingReferences().get(0);
        assertThat(detail.fileId()).isEqualTo(9L);
        assertThat(detail.fileName()).isEqualTo("lost.txt");
        assertThat(detail.storageKey()).isEqualTo("kb/1/lost.txt");
        verify(metrics).recordStorageOrphans("missing", 1);
    }

    private KnowledgeFile file(Long id, String name, String path) {
        KnowledgeFile file = new KnowledgeFile();
        file.setId(id);
        file.setFileName(name);
        file.setFilePath(path);
        return file;
    }

    private UploadSession uploadingSession() {
        UploadSession session = new UploadSession();
        session.setKnowledgeBaseId(1L);
        session.setUploadUuid("s1");
        session.setTotalChunks(2);
        session.setStatus("UPLOADING");
        return session;
    }
}
