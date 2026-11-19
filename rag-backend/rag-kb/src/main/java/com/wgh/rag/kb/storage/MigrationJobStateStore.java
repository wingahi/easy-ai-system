package com.wgh.rag.kb.storage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.common.metrics.RagMetrics;
import com.wgh.rag.config.redis.RedisCommandPort;
import com.wgh.rag.config.redis.RedisKeyNamespace;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 存储迁移任务状态存储：Redis 优先，进程内内存兜底。
 *
 * <p><b>Redis 结构</b></p>
 * <ul>
 *   <li>任务状态：{@code {root}:storage:migrate:job:{taskId}} → JSON 序列化的 {@link MigrationJobView}，
 *       TTL 默认 24 小时（{@code wgh.rag.storage.migration.job-ttl-hours}）；</li>
 *   <li>任务索引：{@code {root}:storage:migrate:index} → JSON 数组，按提交时间倒序的 taskId 列表，
 *       最多 {@code index-limit} 条；TTL 为任务 TTL 的 7 倍，读取时逐个校验任务 key 是否仍存在并过滤过期项；</li>
 *   <li>取消标记：{@code {root}:storage:migrate:cancel:{taskId}} → {@code "1"}，TTL 与任务相同；
 *       任意实例可写，执行实例定期读取。</li>
 * </ul>
 *
 * <p><b>降级</b>：Redis 未启用或命令失败时自动退回进程内状态（本实例可查询、可取消），
 * 首次降级输出 WARN 并上报 {@code wgh.state.store.fallbacks}；失败后 10 秒内不再重试 Redis，
 * 恢复后自动回到 Redis。降级期间多实例之间不共享任务状态，这是明确的行为边界。</p>
 */
@Slf4j
@Component
public class MigrationJobStateStore {

    private static final long REDIS_DOWN_BACKOFF_MILLIS = 10_000L;
    private static final long WARN_INTERVAL_MILLIS = 30_000L;

    private final RedisCommandPort redis;
    private final ObjectMapper objectMapper;
    private final RagMetrics metrics;
    private final StorageMigrationJobProperties properties;

    /** 进程内镜像/兜底：始终写入，保证 Redis 故障时本实例仍可查询。 */
    private final Map<String, MigrationJobView> localJobs = new ConcurrentHashMap<>();
    private final Map<String, Long> localCancel = new ConcurrentHashMap<>();
    private final Deque<String> localIndex = new ArrayDeque<>();

    private volatile long redisDownUntil;
    private volatile long lastWarnAt;
    private volatile boolean disabledWarned;

    public MigrationJobStateStore(RedisCommandPort redis, ObjectMapper objectMapper,
                                  RagMetrics metrics, StorageMigrationJobProperties properties) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.properties = properties;
    }

    /** 任务状态 key：{root}:storage:migrate:job:{taskId} */
    public static String jobKey(String taskId) {
        return RedisKeyNamespace.root() + ":storage:migrate:job:" + sanitize(taskId);
    }

    /** 任务索引 key：{root}:storage:migrate:index */
    public static String indexKey() {
        return RedisKeyNamespace.root() + ":storage:migrate:index";
    }

    /** 取消标记 key：{root}:storage:migrate:cancel:{taskId} */
    public static String cancelKey(String taskId) {
        return RedisKeyNamespace.root() + ":storage:migrate:cancel:" + sanitize(taskId);
    }

    /** 当前是否使用 Redis 共享状态。 */
    public boolean distributed() {
        if (!redis.enabled()) {
            if (!disabledWarned) {
                disabledWarned = true;
                log.warn("Redis 未启用，存储迁移任务状态降级为进程内模式（跨实例查询/取消不生效）");
                metrics.recordStateStoreFallback("storage-migration", "disabled");
            }
            return false;
        }
        return System.currentTimeMillis() >= redisDownUntil;
    }

    /** 写入/更新任务状态（含终态），同时更新进程内镜像。 */
    public void save(MigrationJobView view) {
        if (view == null || view.taskId() == null) {
            return;
        }
        localJobs.put(view.taskId(), view);
        trimLocalJobs();
        if (!distributed()) {
            recordOperation("save", "memory");
            return;
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(view);
        } catch (JsonProcessingException e) {
            log.warn("序列化迁移任务状态失败: taskId={}, error={}", view.taskId(), e.getMessage());
            recordOperation("save", "memory");
            return;
        }
        try {
            redis.set(jobKey(view.taskId()), json, properties.jobTtl());
            recordOperation("save", "redis");
        } catch (RuntimeException e) {
            markRedisDown(e);
            recordOperation("save", "memory");
        }
    }

    /**
     * 读取任务状态；不存在或已过期返回 {@code null}。
     * <p>Redis 可用但 key 已过期时，仅对“仍在运行”的本地任务回退到内存镜像，已结束任务视为过期。</p>
     */
    public MigrationJobView load(String taskId) {
        if (taskId == null) {
            return null;
        }
        if (distributed()) {
            try {
                String json = redis.get(jobKey(taskId));
                recordOperation("load", "redis");
                if (json == null) {
                    MigrationJobView local = localJobs.get(taskId);
                    return local != null && local.status() == MigrationJobStatus.RUNNING ? local : null;
                }
                try {
                    return objectMapper.readValue(json, MigrationJobView.class);
                } catch (JsonProcessingException e) {
                    log.warn("解析迁移任务状态失败，回退进程内状态: taskId={}, error={}", taskId, e.getMessage());
                    return localJobs.get(taskId);
                }
            } catch (RuntimeException e) {
                markRedisDown(e);
            }
        }
        recordOperation("load", "memory");
        return localJobs.get(taskId);
    }

    /** 把任务加入按时间倒序的轻量索引（新任务在前）。 */
    public void addToIndex(String taskId) {
        if (taskId == null) {
            return;
        }
        int limit = Math.max(1, properties.getIndexLimit());
        synchronized (localIndex) {
            localIndex.remove(taskId);
            localIndex.addFirst(taskId);
            while (localIndex.size() > limit) {
                localIndex.removeLast();
            }
        }
        if (!distributed()) {
            recordOperation("index", "memory");
            return;
        }
        try {
            List<String> ids = readIndexFromRedis();
            ids.remove(taskId);
            ids.add(0, taskId);
            if (ids.size() > limit) {
                ids = new ArrayList<>(ids.subList(0, limit));
            }
            redis.set(indexKey(), objectMapper.writeValueAsString(ids), indexTtl());
            recordOperation("index", "redis");
        } catch (JsonProcessingException e) {
            log.warn("序列化迁移任务索引失败: {}", e.getMessage());
        } catch (RuntimeException e) {
            markRedisDown(e);
            recordOperation("index", "memory");
        }
    }

    /** 按时间倒序返回最近的任务 id，读取时过滤已过期（任务 key 不存在）的索引项。 */
    public List<String> recentTaskIds(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, Math.max(1, properties.getIndexLimit())));
        if (distributed()) {
            try {
                String json = redis.get(indexKey());
                recordOperation("index", "redis");
                if (json != null && !json.isBlank()) {
                    List<String> ids;
                    try {
                        ids = objectMapper.readValue(json, new TypeReference<List<String>>() {
                        });
                    } catch (JsonProcessingException e) {
                        log.warn("解析迁移任务索引失败，忽略索引: {}", e.getMessage());
                        return List.of();
                    }
                    List<String> valid = new ArrayList<>();
                    for (String id : ids) {
                        if (valid.size() >= safeLimit) {
                            break;
                        }
                        if (redis.get(jobKey(id)) != null) {
                            valid.add(id);
                        }
                    }
                    return valid;
                }
            } catch (RuntimeException e) {
                markRedisDown(e);
            }
        }
        recordOperation("index", "memory");
        List<String> valid = new ArrayList<>();
        synchronized (localIndex) {
            for (String id : localIndex) {
                if (valid.size() >= safeLimit) {
                    break;
                }
                if (localJobs.containsKey(id)) {
                    valid.add(id);
                }
            }
        }
        return valid;
    }

    /** 写入取消标记：任意实例可见。 */
    public void requestCancel(String taskId) {
        if (taskId == null) {
            return;
        }
        localCancel.put(taskId, System.currentTimeMillis() + properties.jobTtl().toMillis());
        if (!distributed()) {
            recordOperation("cancel", "memory");
            return;
        }
        try {
            redis.set(cancelKey(taskId), "1", properties.jobTtl());
            recordOperation("cancel", "redis");
        } catch (RuntimeException e) {
            markRedisDown(e);
            recordOperation("cancel", "memory");
        }
    }

    /** 读取取消标记（执行实例定期调用）。 */
    public boolean isCancelRequested(String taskId) {
        if (taskId == null) {
            return false;
        }
        Long expireAt = localCancel.get(taskId);
        if (expireAt != null) {
            if (expireAt > System.currentTimeMillis()) {
                return true;
            }
            localCancel.remove(taskId);
        }
        if (!distributed()) {
            recordOperation("cancel-check", "memory");
            return false;
        }
        try {
            boolean requested = redis.get(cancelKey(taskId)) != null;
            recordOperation("cancel-check", "redis");
            return requested;
        } catch (RuntimeException e) {
            markRedisDown(e);
            return false;
        }
    }

    /** 终态后清理取消标记。 */
    public void clearCancel(String taskId) {
        if (taskId == null) {
            return;
        }
        localCancel.remove(taskId);
        if (!distributed()) {
            recordOperation("cancel-clear", "memory");
            return;
        }
        try {
            redis.delete(cancelKey(taskId));
            recordOperation("cancel-clear", "redis");
        } catch (RuntimeException e) {
            markRedisDown(e);
        }
    }

    private List<String> readIndexFromRedis() {
        String json = redis.get(indexKey());
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return new ArrayList<>(objectMapper.readValue(json, new TypeReference<List<String>>() {
            }));
        } catch (JsonProcessingException e) {
            log.warn("解析迁移任务索引失败，按空索引处理: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private void trimLocalJobs() {
        int limit = Math.max(1, properties.getIndexLimit());
        if (localJobs.size() <= limit) {
            return;
        }
        int overflow = localJobs.size() - limit;
        localJobs.values().stream()
                .sorted(Comparator.comparing(MigrationJobView::startedAt))
                .limit(overflow)
                .map(MigrationJobView::taskId)
                .toList()
                .forEach(localJobs::remove);
    }

    private void recordOperation(String operation, String backend) {
        metrics.recordStateStoreOperation("storage-migration", operation, backend);
    }

    private void markRedisDown(RuntimeException e) {
        long now = System.currentTimeMillis();
        boolean wasUp = now >= redisDownUntil;
        redisDownUntil = now + REDIS_DOWN_BACKOFF_MILLIS;
        if (wasUp) {
            metrics.recordStateStoreFallback("storage-migration", "error");
        }
        if (now - lastWarnAt >= WARN_INTERVAL_MILLIS) {
            lastWarnAt = now;
            log.warn("Redis 不可用，存储迁移任务状态降级为进程内模式（跨实例查询/取消暂不生效），{} 秒内不再重试: {}",
                    REDIS_DOWN_BACKOFF_MILLIS / 1000, e.getMessage());
        }
    }

    private java.time.Duration indexTtl() {
        return properties.jobTtl().multipliedBy(7);
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        return value.trim().replaceAll("[^A-Za-z0-9._:-]", "_");
    }
}
