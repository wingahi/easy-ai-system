package com.wgh.rag.storage.migration;

import com.wgh.rag.common.metrics.RagMetrics;
import com.wgh.rag.storage.properties.StorageMigrationProperties;
import com.wgh.rag.storage.properties.StorageProperties;
import com.wgh.rag.storage.service.FileStorageService;
import com.wgh.rag.storage.service.LocalFileStorageService;
import com.wgh.rag.storage.service.StorageBackend;
import com.wgh.rag.storage.service.StorageObjectInfo;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 存储迁移：写入 + 大小/哈希校验、幂等续跑、dry-run、哈希漂移后重搬。
 * <p>
 * 用两个本地根目录模拟“源/目标”后端，避免依赖真实 OSS。
 */
class StorageMigrationServiceTest {

    @TempDir
    Path sourceDir;

    @TempDir
    Path targetDir;

    private LocalFileStorageService sourceService;
    private LocalFileStorageService targetService;
    private StorageMigrationService migrationService;

    @BeforeEach
    void setUp() {
        sourceService = local(sourceDir);
        targetService = local(targetDir);
        migrationService = new StorageMigrationService(new StorageMigrationProperties(),
                new RagMetrics(new SimpleMeterRegistry()));
    }

    @Test
    @DisplayName("迁移对象并校验大小与哈希")
    void migrateCopiesAndVerifies() throws IOException {
        byte[] alpha = "alpha".getBytes(StandardCharsets.UTF_8);
        byte[] beta = "bravo-content".getBytes(StandardCharsets.UTF_8);
        sourceService.store("kb/1/u1/a.txt", new ByteArrayInputStream(alpha), alpha.length, null);
        sourceService.store("kb/2/u2/parts/0.part", new ByteArrayInputStream(beta), beta.length, null);

        MigrationReport report = migrationService.migrate(
                backend(sourceService), backend(targetService), false, 2, 2);

        assertThat(report.total()).isEqualTo(2);
        assertThat(report.copied()).isEqualTo(2);
        assertThat(report.failed()).isZero();
        assertThat(report.copiedBytes()).isEqualTo(alpha.length + beta.length);
        assertThat(read(targetService, "kb/1/u1/a.txt")).isEqualTo(alpha);
        assertThat(read(targetService, "kb/2/u2/parts/0.part")).isEqualTo(beta);
    }

    @Test
    @DisplayName("重复迁移幂等：目标已存在且一致时全部跳过")
    void migrateIsIdempotent() {
        byte[] content = "same".getBytes(StandardCharsets.UTF_8);
        sourceService.store("kb/1/u1/a.txt", new ByteArrayInputStream(content), content.length, null);

        migrationService.migrate(backend(sourceService), backend(targetService), false, 2, 2);
        MigrationReport second = migrationService.migrate(
                backend(sourceService), backend(targetService), false, 2, 2);

        assertThat(second.total()).isEqualTo(1);
        assertThat(second.copied()).isZero();
        assertThat(second.skipped()).isEqualTo(1);
    }

    @Test
    @DisplayName("目标内容哈希漂移时重新搬运")
    void migrateRecopiesWhenTargetHashDiffers() throws IOException {
        byte[] content = "alpha".getBytes(StandardCharsets.UTF_8);
        sourceService.store("kb/1/u1/a.txt", new ByteArrayInputStream(content), content.length, null);
        migrationService.migrate(backend(sourceService), backend(targetService), false, 1, 2);

        // 同长度、不同内容：大小校验通过，只有哈希校验能发现漂移
        Files.write(targetDir.resolve("kb/1/u1/a.txt"), "ALPHA".getBytes(StandardCharsets.UTF_8));

        MigrationReport report = migrationService.migrate(
                backend(sourceService), backend(targetService), false, 1, 2);

        assertThat(report.copied()).isEqualTo(1);
        assertThat(report.skipped()).isZero();
        assertThat(read(targetService, "kb/1/u1/a.txt")).isEqualTo(content);
    }

    @Test
    @DisplayName("dry-run 不写入目标，只报告待迁移对象")
    void dryRunDoesNotWrite() {
        byte[] content = "dry".getBytes(StandardCharsets.UTF_8);
        sourceService.store("kb/1/u1/a.txt", new ByteArrayInputStream(content), content.length, null);

        MigrationReport report = migrationService.migrate(
                backend(sourceService), backend(targetService), true, 2, 2);

        assertThat(report.dryRun()).isTrue();
        assertThat(report.planned()).isEqualTo(1);
        assertThat(report.copied()).isZero();
        assertThat(targetService.exists("kb/1/u1/a.txt")).isFalse();
    }

    @Test
    @DisplayName("进度监听：回调总数与每个对象的处理结果")
    void reportsProgressToListener() {
        byte[] first = "one".getBytes(StandardCharsets.UTF_8);
        byte[] second = "two".getBytes(StandardCharsets.UTF_8);
        sourceService.store("kb/1/u1/a.txt", new ByteArrayInputStream(first), first.length, null);
        sourceService.store("kb/1/u2/b.txt", new ByteArrayInputStream(second), second.length, null);

        AtomicInteger total = new AtomicInteger(-1);
        List<MigrationOutcome> outcomes = Collections.synchronizedList(new ArrayList<>());
        MigrationProgressListener listener = new MigrationProgressListener() {
            @Override
            public void onTotal(int count) {
                total.set(count);
            }

            @Override
            public void onObjectFinished(StorageObjectInfo object, MigrationOutcome outcome, long copiedBytes) {
                outcomes.add(outcome);
            }

            @Override
            public boolean isCancelled() {
                return false;
            }
        };

        MigrationReport report = migrationService.migrate(
                backend(sourceService), backend(targetService), false, 1, 1, listener);

        assertThat(total.get()).isEqualTo(2);
        assertThat(outcomes).hasSize(2).containsOnly(MigrationOutcome.COPIED);
        assertThat(report.copied()).isEqualTo(2);
    }

    @Test
    @DisplayName("协作式取消：处理完当前对象后不再派发后续对象")
    void cancellationStopsRemainingObjects() {
        byte[] content = "cancel".getBytes(StandardCharsets.UTF_8);
        sourceService.store("kb/1/u1/a.txt", new ByteArrayInputStream(content), content.length, null);
        sourceService.store("kb/1/u2/b.txt", new ByteArrayInputStream(content), content.length, null);

        AtomicBoolean cancelRequested = new AtomicBoolean(false);
        AtomicInteger finished = new AtomicInteger();
        MigrationProgressListener listener = new MigrationProgressListener() {
            @Override
            public void onTotal(int total) {
            }

            @Override
            public void onObjectFinished(StorageObjectInfo object, MigrationOutcome outcome, long copiedBytes) {
                finished.incrementAndGet();
                cancelRequested.set(true);
            }

            @Override
            public boolean isCancelled() {
                return cancelRequested.get();
            }
        };

        MigrationReport report = migrationService.migrate(
                backend(sourceService), backend(targetService), false, 1, 1, listener);

        assertThat(report.total()).isEqualTo(2);
        assertThat(report.copied()).isEqualTo(1);
        assertThat(finished.get()).isEqualTo(1);
    }

    private LocalFileStorageService local(Path dir) {
        StorageProperties properties = new StorageProperties();
        properties.getLocal().setBaseDir(dir.toString());
        LocalFileStorageService service = new LocalFileStorageService(properties);
        service.init();
        return service;
    }

    private StorageBackend backend(LocalFileStorageService service) {
        return new StorageBackend("local", service, service, null);
    }

    private byte[] read(FileStorageService service, String key) throws IOException {
        try (InputStream in = service.openStream(key)) {
            return in.readAllBytes();
        }
    }
}
