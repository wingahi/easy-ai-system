package com.wgh.rag.kb.storage;

import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.metrics.RagMetrics;
import com.wgh.rag.config.redis.RedisDistributedLock;
import com.wgh.rag.config.redis.RedisUnavailableException;
import com.wgh.rag.storage.migration.MigrationOutcome;
import com.wgh.rag.storage.migration.MigrationProgressListener;
import com.wgh.rag.storage.migration.MigrationReport;
import com.wgh.rag.storage.migration.StorageMigrationService;
import com.wgh.rag.storage.service.StorageObjectInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 异步迁移作业：立即返回、实时进度、重复提交拒绝、取消、失败、Redis 单飞与降级、进度节流。
 */
class StorageMigrationJobServiceTest {

    private StorageMigrationService migrationService;
    private MigrationJobStateStore stateStore;
    private RedisDistributedLock distributedLock;
    private StorageMigrationJobProperties properties;
    private RagMetrics metrics;
    private StorageMigrationJobService jobService;
    private CountDownLatch started;
    private CountDownLatch release;

    @BeforeEach
    void setUp() {
        migrationService = mock(StorageMigrationService.class);
        stateStore = mock(MigrationJobStateStore.class);
        distributedLock = mock(RedisDistributedLock.class);
        properties = new StorageMigrationJobProperties();
        metrics = mock(RagMetrics.class);
        RedisDistributedLock.LockHandle defaultHandle = new RedisDistributedLock.LockHandle(
                "wgh:lock:" + StorageMigrationJobService.LOCK_NAME, StorageMigrationJobService.LOCK_NAME,
                "owner", Duration.ofSeconds(120));
        when(distributedLock.enabled()).thenReturn(true);
        // 默认第一次能拿到锁，后续视为已被占用（用于“运行中重复提交”场景）
        when(distributedLock.tryAcquire(anyString(), any(Duration.class))).thenReturn(defaultHandle, null);
        when(distributedLock.renew(any(), any(Duration.class))).thenReturn(true);
        when(distributedLock.unlock(any())).thenReturn(true);
        jobService = new StorageMigrationJobService(migrationService, stateStore, distributedLock,
                properties, metrics);
        started = new CountDownLatch(1);
        release = new CountDownLatch(1);
    }

    @AfterEach
    void tearDown() {
        release.countDown();
        jobService.shutdown();
    }

    @Test
    @DisplayName("提交立即返回 taskId，后台完成后可查询完整进度与报告")
    void submitReturnsImmediatelyAndCompletes() throws Exception {
        when(migrationService.migrate(anyString(), anyString(), anyBoolean(), any(), any(),
                any(MigrationProgressListener.class)))
                .thenAnswer(invocation -> {
                    MigrationProgressListener listener = invocation.getArgument(5);
                    started.countDown();
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test release timeout");
                    }
                    listener.onTotal(3);
                    listener.onObjectFinished(objectInfo(), MigrationOutcome.COPIED, 4);
                    listener.onObjectFinished(objectInfo(), MigrationOutcome.SKIPPED, 0);
                    listener.onObjectFinished(objectInfo(), MigrationOutcome.FAILED, 0);
                    return new MigrationReport("local", "oss", false, 3, 1, 1, 0, 1, 4, 10L,
                            List.of("kb/1/u/x.txt: boom"));
                });

        MigrationJobView submitted = jobService.submit("local", "oss", false, 2, 3);

        assertThat(submitted.taskId()).isNotBlank();
        assertThat(submitted.status()).isEqualTo(MigrationJobStatus.RUNNING);
        assertThat(submitted.totalKnown()).isFalse();
        assertThat(submitted.report()).isNull();

        assertThatThrownBy(() -> jobService.submit("local", "oss", false, 2, 3))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已有迁移任务在执行");

        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        release.countDown();

        MigrationJobView done = awaitStatus(submitted.taskId(), MigrationJobStatus.COMPLETED);
        assertThat(done.totalKnown()).isTrue();
        assertThat(done.total()).isEqualTo(3);
        assertThat(done.processed()).isEqualTo(3);
        assertThat(done.copied()).isEqualTo(1);
        assertThat(done.skipped()).isEqualTo(1);
        assertThat(done.failed()).isEqualTo(1);
        assertThat(done.copiedBytes()).isEqualTo(4);
        assertThat(done.report()).isNotNull();
        assertThat(done.report().failures()).containsExactly("kb/1/u/x.txt: boom");
        assertThat(done.startedAt()).isNotNull();
        assertThat(done.finishedAt()).isNotNull();
        assertThat(jobService.cancel(submitted.taskId())).isFalse();
    }

    @Test
    @DisplayName("取消：任务进入 CANCELLED，保留已处理进度")
    void cancelMarksTaskCancelled() throws Exception {
        when(migrationService.migrate(anyString(), anyString(), anyBoolean(), any(), any(),
                any(MigrationProgressListener.class)))
                .thenAnswer(invocation -> {
                    MigrationProgressListener listener = invocation.getArgument(5);
                    listener.onTotal(2);
                    listener.onObjectFinished(objectInfo(), MigrationOutcome.COPIED, 3);
                    started.countDown();
                    long deadline = System.currentTimeMillis() + 5000L;
                    while (!listener.isCancelled() && System.currentTimeMillis() < deadline) {
                        Thread.sleep(5);
                    }
                    return new MigrationReport("local", "oss", false, 2, 1, 0, 0, 0, 3, 5L, List.of());
                });

        MigrationJobView submitted = jobService.submit("local", "oss", false, 1, 1);
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(jobService.cancel(submitted.taskId())).isTrue();

        MigrationJobView done = awaitStatus(submitted.taskId(), MigrationJobStatus.CANCELLED);
        assertThat(done.cancelRequested()).isTrue();
        assertThat(done.total()).isEqualTo(2);
        assertThat(done.processed()).isEqualTo(1);
        assertThat(done.copied()).isEqualTo(1);
    }

    @Test
    @DisplayName("其他实例写入取消标记后，执行实例感知并转为 CANCELLED")
    void remoteCancelIsObservedByExecutingInstance() throws Exception {
        properties.setCancelCheckIntervalMillis(10);
        when(stateStore.isCancelRequested(anyString())).thenReturn(true);
        when(migrationService.migrate(anyString(), anyString(), anyBoolean(), any(), any(),
                any(MigrationProgressListener.class)))
                .thenAnswer(invocation -> {
                    MigrationProgressListener listener = invocation.getArgument(5);
                    listener.onTotal(2);
                    long deadline = System.currentTimeMillis() + 5000L;
                    while (!listener.isCancelled() && System.currentTimeMillis() < deadline) {
                        Thread.sleep(5);
                    }
                    return new MigrationReport("local", "oss", false, 2, 0, 0, 0, 0, 0, 1L, List.of());
                });

        MigrationJobView submitted = jobService.submit("local", "oss", false, 1, 1);
        MigrationJobView done = awaitStatus(submitted.taskId(), MigrationJobStatus.CANCELLED);

        assertThat(done.cancelRequested()).isTrue();
    }

    @Test
    @DisplayName("锁被其他实例持有：返回“已有迁移任务在执行”，不计锁降级指标")
    void lockHeldRejectsWithExistingTaskMessage() {
        when(distributedLock.tryAcquire(anyString(), any(Duration.class))).thenReturn(null);

        assertThatThrownBy(() -> jobService.submit("local", "oss", true, null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已有迁移任务在执行");
        verify(metrics, never()).recordStateStoreFallback(eq("storage-migration-lock"), anyString());
    }

    @Test
    @DisplayName("Redis 未启用：拒绝启动并上报 disabled 指标")
    void redisDisabledRejectsSubmit() {
        when(distributedLock.enabled()).thenReturn(false);

        assertThatThrownBy(() -> jobService.submit("local", "oss", true, null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("分布式锁不可用，已拒绝启动迁移");
        verify(distributedLock, never()).tryAcquire(anyString(), any(Duration.class));
        verify(metrics).recordStateStoreFallback("storage-migration-lock", "disabled");
    }

    @Test
    @DisplayName("Redis 命令失败：拒绝启动并上报 error 指标")
    void redisLockErrorRejectsSubmit() {
        when(distributedLock.tryAcquire(anyString(), any(Duration.class)))
                .thenThrow(new RedisUnavailableException("redis down"));

        assertThatThrownBy(() -> jobService.submit("local", "oss", true, null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("分布式锁不可用，已拒绝启动迁移");
        verify(metrics).recordStateStoreFallback("storage-migration-lock", "error");
        verify(migrationService, never()).migrate(anyString(), anyString(), anyBoolean(), any(), any(),
                any(MigrationProgressListener.class));
    }

    @Test
    @DisplayName("Redis 中途失效续约失败不终止正在跑的迁移")
    void renewalFailureDoesNotCancelRunningJob() throws Exception {
        properties.setLockRenewIntervalSeconds(1);
        CountDownLatch renewCalled = new CountDownLatch(1);
        when(distributedLock.renew(any(), any(Duration.class))).thenAnswer(invocation -> {
            renewCalled.countDown();
            throw new RedisUnavailableException("redis down");
        });
        when(migrationService.migrate(anyString(), anyString(), anyBoolean(), any(), any(),
                any(MigrationProgressListener.class)))
                .thenAnswer(invocation -> {
                    started.countDown();
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test release timeout");
                    }
                    return new MigrationReport("local", "oss", false, 1, 1, 0, 0, 0, 1, 1L, List.of());
                });

        MigrationJobView submitted = jobService.submit("local", "oss", false, 1, 1);
        assertThat(renewCalled.await(5, TimeUnit.SECONDS)).isTrue();
        release.countDown();

        MigrationJobView done = awaitStatus(submitted.taskId(), MigrationJobStatus.COMPLETED);
        assertThat(done.status()).isEqualTo(MigrationJobStatus.COMPLETED);
    }

    @Test
    @DisplayName("状态写入失败不中断搬运，任务仍可完成")
    void stateWriteFailureDoesNotAbortMigration() throws Exception {
        doThrow(new IllegalStateException("redis down")).when(stateStore).save(any());
        when(migrationService.migrate(anyString(), anyString(), anyBoolean(), any(), any(),
                any(MigrationProgressListener.class)))
                .thenReturn(new MigrationReport("local", "oss", false, 1, 1, 0, 0, 0, 1, 1L, List.of()));

        MigrationJobView submitted = jobService.submit("local", "oss", false, 1, 1);

        MigrationJobView done = awaitStatus(submitted.taskId(), MigrationJobStatus.COMPLETED);
        assertThat(done.report()).isNotNull();
    }

    @Test
    @DisplayName("Redis 单飞锁成功后任务结束会释放锁")
    void redisLockIsReleasedAfterCompletion() throws Exception {
        RedisDistributedLock.LockHandle handle = new RedisDistributedLock.LockHandle(
                "wgh:lock:storage:migrate:single-flight", StorageMigrationJobService.LOCK_NAME,
                "owner", Duration.ofSeconds(120));
        when(distributedLock.enabled()).thenReturn(true);
        when(distributedLock.tryAcquire(anyString(), any(Duration.class))).thenReturn(handle);
        when(distributedLock.renew(any(), any(Duration.class))).thenReturn(true);
        when(distributedLock.unlock(handle)).thenReturn(true);
        when(migrationService.migrate(anyString(), anyString(), anyBoolean(), any(), any(),
                any(MigrationProgressListener.class)))
                .thenReturn(new MigrationReport("local", "oss", false, 0, 0, 0, 0, 0, 0, 1L, List.of()));

        MigrationJobView submitted = jobService.submit("local", "oss", false, 1, 1);
        awaitStatus(submitted.taskId(), MigrationJobStatus.COMPLETED);

        verify(distributedLock).unlock(handle);
        verify(metrics).recordStateStoreOperation("storage-migration", "lock", "redis");
    }

    @Test
    @DisplayName("进度写入按数量阈值合并，终态立即写入")
    void progressWritesAreThrottledByBatch() throws Exception {
        properties.setProgressFlushBatch(2);
        properties.setProgressFlushIntervalMillis(60_000L);
        when(migrationService.migrate(anyString(), anyString(), anyBoolean(), any(), any(),
                any(MigrationProgressListener.class)))
                .thenAnswer(invocation -> {
                    MigrationProgressListener listener = invocation.getArgument(5);
                    listener.onTotal(5);
                    for (int i = 0; i < 5; i++) {
                        listener.onObjectFinished(objectInfo(), MigrationOutcome.COPIED, 1);
                    }
                    return new MigrationReport("local", "oss", false, 5, 5, 0, 0, 0, 5, 1L, List.of());
                });

        MigrationJobView submitted = jobService.submit("local", "oss", false, 1, 1);
        awaitStatus(submitted.taskId(), MigrationJobStatus.COMPLETED);

        // 初始 1 次 + onTotal 1 次 + 批次 2 次（第 2、4 个对象）+ 终态 1 次 = 5 次
        verify(stateStore, times(5)).save(any());
    }

    @Test
    @DisplayName("列出最近任务时跳过已过期的任务")
    void listRecentSkipsExpiredJobs() {
        MigrationJobView alive = view("t1");
        when(stateStore.recentTaskIds(5)).thenReturn(List.of("t1", "t2"));
        when(stateStore.load("t1")).thenReturn(alive);
        when(stateStore.load("t2")).thenReturn(null);

        List<MigrationJobView> views = jobService.listRecent(5);

        assertThat(views).containsExactly(alive);
    }

    @Test
    @DisplayName("引擎级异常：任务进入 FAILED 并保留错误信息")
    void engineFailureMarksFailed() throws Exception {
        when(migrationService.migrate(anyString(), anyString(), anyBoolean(), any(), any(),
                any(MigrationProgressListener.class)))
                .thenThrow(new BusinessException("OSS 存储未配置完整"));

        MigrationJobView submitted = jobService.submit("local", "oss", true, null, null);
        MigrationJobView done = awaitStatus(submitted.taskId(), MigrationJobStatus.FAILED);

        assertThat(done.errorMessage()).contains("OSS 存储未配置完整");
        assertThat(done.report()).isNull();
        assertThat(done.finishedAt()).isNotNull();
    }

    @Test
    @DisplayName("查询/取消不存在的任务给出明确错误")
    void unknownTaskIsReported() {
        assertThatThrownBy(() -> jobService.get("missing"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不存在");
        assertThatThrownBy(() -> jobService.cancel("missing"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不存在");
    }

    private MigrationJobView awaitStatus(String taskId, MigrationJobStatus expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        MigrationJobView view = jobService.get(taskId);
        while (view.status() != expected && System.nanoTime() < deadline) {
            Thread.sleep(10);
            view = jobService.get(taskId);
        }
        assertThat(view.status()).isEqualTo(expected);
        return view;
    }

    private StorageObjectInfo objectInfo() {
        return new StorageObjectInfo("kb/1/u/x.txt", 4, System.currentTimeMillis());
    }

    private MigrationJobView view(String taskId) {
        LocalDateTime startedAt = LocalDateTime.of(2026, 1, 1, 10, 0);
        return new MigrationJobView(taskId, MigrationJobStatus.COMPLETED, "local", "oss", false,
                1, true, 1, 1, 0, 0, 0, 1L, startedAt, startedAt.plusMinutes(1),
                null, false, new MigrationReport("local", "oss", false, 1, 1, 0, 0, 0, 1L, 1L, List.of()));
    }
}
