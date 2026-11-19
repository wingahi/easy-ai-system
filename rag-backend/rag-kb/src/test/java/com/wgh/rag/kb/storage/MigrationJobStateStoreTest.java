package com.wgh.rag.kb.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.common.metrics.RagMetrics;
import com.wgh.rag.config.redis.RedisCommandPort;
import com.wgh.rag.config.redis.RedisKeyNamespace;
import com.wgh.rag.storage.migration.MigrationReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 迁移任务状态存储：Redis key/TTL、跨实例查询与取消、索引过滤、降级与过期。
 */
class MigrationJobStateStoreTest {

    private FakeRedis redis;
    private ObjectMapper objectMapper;
    private RagMetrics metrics;
    private StorageMigrationJobProperties properties;
    private MigrationJobStateStore store;

    @BeforeEach
    void setUp() {
        redis = new FakeRedis();
        objectMapper = new ObjectMapper().findAndRegisterModules();
        metrics = mock(RagMetrics.class);
        properties = new StorageMigrationJobProperties();
        store = new MigrationJobStateStore(redis, objectMapper, metrics, properties);
    }

    @Test
    @DisplayName("状态以 {root}:storage:migrate:job:{taskId} 存储并带 24h TTL，其他实例可读取")
    void savesStructuredStateWithTtlAndIsReadableFromAnotherInstance() {
        MigrationJobView view = view("t1", MigrationJobStatus.COMPLETED, true);

        store.save(view);

        String expectedKey = RedisKeyNamespace.root() + ":storage:migrate:job:t1";
        assertThat(redis.values).containsKey(expectedKey);
        assertThat(redis.ttls.get(expectedKey)).isEqualTo(Duration.ofHours(24));

        MigrationJobStateStore otherInstance = new MigrationJobStateStore(redis, objectMapper, metrics, properties);
        assertThat(otherInstance.load("t1")).isEqualTo(view);
    }

    @Test
    @DisplayName("任务 key 过期后查询返回 null；运行中任务回退本实例内存镜像")
    void expiredTaskIsNotVisibleButRunningTaskFallsBackToMemory() {
        store.save(view("done", MigrationJobStatus.COMPLETED, true));
        redis.values.remove(MigrationJobStateStore.jobKey("done"));
        assertThat(store.load("done")).isNull();

        store.save(view("running", MigrationJobStatus.RUNNING, false));
        redis.values.remove(MigrationJobStateStore.jobKey("running"));
        assertThat(store.load("running")).isNotNull();
    }

    @Test
    @DisplayName("索引按提交时间倒序，并在读取时过滤已过期任务")
    void indexIsNewestFirstAndFiltersExpiredEntries() {
        store.save(view("t1", MigrationJobStatus.COMPLETED, true));
        store.save(view("t2", MigrationJobStatus.COMPLETED, true));
        store.save(view("t3", MigrationJobStatus.COMPLETED, true));
        store.addToIndex("t1");
        store.addToIndex("t2");
        store.addToIndex("t3");

        assertThat(store.recentTaskIds(2)).containsExactly("t3", "t2");

        redis.values.remove(MigrationJobStateStore.jobKey("t3"));
        assertThat(store.recentTaskIds(5)).containsExactly("t2", "t1");
    }

    @Test
    @DisplayName("取消标记跨实例可见，清理后新实例不再读到")
    void cancelMarkerIsSharedAcrossInstances() {
        store.requestCancel("t1");

        MigrationJobStateStore otherInstance = new MigrationJobStateStore(redis, objectMapper, metrics, properties);
        assertThat(otherInstance.isCancelRequested("t1")).isTrue();

        otherInstance.clearCancel("t1");
        MigrationJobStateStore thirdInstance = new MigrationJobStateStore(redis, objectMapper, metrics, properties);
        assertThat(thirdInstance.isCancelRequested("t1")).isFalse();
    }

    @Test
    @DisplayName("Redis 未启用时降级到进程内状态，只上报一次 disabled 指标")
    void fallsBackToMemoryWhenRedisDisabled() {
        redis.enabled = false;

        store.save(view("t1", MigrationJobStatus.RUNNING, false));
        assertThat(store.distributed()).isFalse();
        assertThat(store.load("t1")).isNotNull();
        store.save(view("t2", MigrationJobStatus.RUNNING, false));

        verify(metrics, times(1)).recordStateStoreFallback("storage-migration", "disabled");
    }

    @Test
    @DisplayName("Redis 写失败时降级到进程内状态并上报 error 指标")
    void fallsBackToMemoryWhenRedisFails() {
        redis.failWrites = true;

        store.save(view("t1", MigrationJobStatus.RUNNING, false));

        assertThat(store.load("t1")).isNotNull();
        verify(metrics, times(1)).recordStateStoreFallback("storage-migration", "error");
    }

    private MigrationJobView view(String taskId, MigrationJobStatus status, boolean finished) {
        LocalDateTime startedAt = LocalDateTime.of(2026, 1, 1, 10, 0);
        LocalDateTime finishedAt = finished ? startedAt.plusMinutes(1) : null;
        MigrationReport report = finished
                ? new MigrationReport("local", "oss", false, 10, 8, 1, 0, 1, 100L, 1000L, List.of("k: err"))
                : null;
        return new MigrationJobView(taskId, status, "local", "oss", false, 10, true,
                9, 8, 1, 0, 1, 100L, startedAt, finishedAt, null, false, report);
    }

    /** 最小 Redis 桩：只保留 get/set/delete 与 TTL 记录。 */
    private static final class FakeRedis implements RedisCommandPort {

        private boolean enabled = true;
        private boolean failWrites = false;
        private final Map<String, String> values = new ConcurrentHashMap<>();
        private final Map<String, Duration> ttls = new ConcurrentHashMap<>();

        @Override
        public boolean enabled() {
            return enabled;
        }

        @Override
        public Long executeScript(String script, List<String> keys, List<String> args) {
            throw new UnsupportedOperationException("测试桩不支持 Lua");
        }

        @Override
        public String get(String key) {
            if (failWrites) {
                throw new IllegalStateException("redis down");
            }
            return values.get(key);
        }

        @Override
        public void set(String key, String value, Duration ttl) {
            if (failWrites) {
                throw new IllegalStateException("redis down");
            }
            values.put(key, value);
            ttls.put(key, ttl);
        }

        @Override
        public void delete(String key) {
            values.remove(key);
        }
    }
}
