package com.wgh.rag.chat.service;

import com.wgh.rag.common.metrics.RagMetrics;
import com.wgh.rag.config.redis.RedisCommandPort;
import com.wgh.rag.config.redis.RedisKeyNamespace;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 跨实例取消标记存储。
 *
 * <p>标记以 Redis 为准，使取消请求落到任意实例都能被正在生成流的实例感知；
 * Redis 不可用时降级为进程内标记（仅本实例生效），并输出 WARN + 指标，
 * 避免“多实例各自取消”静默发生。</p>
 *
 * <p>标记按会话维度（{@code s:{sessionId}}）存短期 TTL；消费后立即删除，
 * 防止同一会话的下一次生成被历史取消标记误伤。</p>
 */
@Slf4j
@Component
public class ChatCancellationStore {

    /** 取消标记 TTL：覆盖单次生成的最长耗时即可，过期自动清理 */
    static final Duration CANCEL_TTL = Duration.ofMinutes(10);
    private static final long REDIS_DOWN_BACKOFF_MILLIS = 10_000L;
    private static final long WARN_INTERVAL_MILLIS = 30_000L;

    private final RedisCommandPort redis;
    private final RagMetrics metrics;
    private final Map<String, Long> localMarks = new ConcurrentHashMap<>();

    private volatile long redisDownUntil;
    private volatile long lastWarnAt;
    private volatile boolean disabledWarned;

    public ChatCancellationStore(RedisCommandPort redis, RagMetrics metrics) {
        this.redis = redis;
        this.metrics = metrics;
    }

    /** 取消标记的 key（会话维度）：{root}:chat:cancel:s:{sessionId} */
    public static String sessionKey(Long sessionId) {
        return RedisKeyNamespace.root() + ":chat:cancel:s:" + sessionId;
    }

    /**
     * 写入取消标记。
     *
     * @return true 表示写入了共享存储（Redis），false 表示降级为进程内标记
     */
    public boolean cancel(String key, String source) {
        if (key == null || key.isBlank()) {
            return false;
        }
        if (redisUsable()) {
            try {
                redis.set(key, "1", CANCEL_TTL);
                metrics.recordStateStoreOperation("cancellation", "cancel", "redis");
                metrics.recordChatCancellation(source, "redis");
                return true;
            } catch (RuntimeException e) {
                markRedisDown(e);
            }
        }
        localMarks.put(key, System.currentTimeMillis() + CANCEL_TTL.toMillis());
        metrics.recordStateStoreOperation("cancellation", "cancel", "memory");
        metrics.recordChatCancellation(source, "memory");
        return false;
    }

    /**
     * 查询并消费取消标记：命中后删除，保证只影响当前一次生成。
     */
    public boolean consume(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        if (redisUsable()) {
            try {
                String value = redis.get(key);
                if (value != null) {
                    redis.delete(key);
                    metrics.recordStateStoreOperation("cancellation", "consume", "redis");
                    metrics.recordChatCancellation("remote", "redis");
                    return true;
                }
                metrics.recordStateStoreOperation("cancellation", "consume", "redis");
                return false;
            } catch (RuntimeException e) {
                markRedisDown(e);
            }
        }
        Long expireAt = localMarks.remove(key);
        metrics.recordStateStoreOperation("cancellation", "consume", "memory");
        return expireAt != null && expireAt > System.currentTimeMillis();
    }

    /**
     * 清除取消标记（不清求标记内容）。
     * <p>新一轮生成开始时调用，丢弃上一次生成遗留的停止请求，
     * 避免“上一次的显式停止”误伤同一会话的下一次生成。</p>
     */
    public void clear(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        localMarks.remove(key);
        if (redisUsable()) {
            try {
                redis.delete(key);
                metrics.recordStateStoreOperation("cancellation", "clear", "redis");
                return;
            } catch (RuntimeException e) {
                markRedisDown(e);
            }
        }
        metrics.recordStateStoreOperation("cancellation", "clear", "memory");
    }

    /** 当前是否使用 Redis 共享标记 */
    public boolean distributed() {
        return redis.enabled() && System.currentTimeMillis() >= redisDownUntil;
    }

    private boolean redisUsable() {
        if (!redis.enabled()) {
            if (!disabledWarned) {
                disabledWarned = true;
                log.warn("Redis 未启用，对话取消标记使用单机内存模式（跨实例取消不生效）");
                metrics.recordStateStoreFallback("cancellation", "disabled");
            }
            return false;
        }
        return System.currentTimeMillis() >= redisDownUntil;
    }

    private void markRedisDown(RuntimeException e) {
        long now = System.currentTimeMillis();
        boolean wasUp = now >= redisDownUntil;
        redisDownUntil = now + REDIS_DOWN_BACKOFF_MILLIS;
        if (wasUp) {
            metrics.recordStateStoreFallback("cancellation", "error");
        }
        if (now - lastWarnAt >= WARN_INTERVAL_MILLIS) {
            lastWarnAt = now;
            log.warn("Redis 不可用，对话取消标记降级为单机内存模式（跨实例取消暂不生效），{} 秒内不再重试: {}",
                    REDIS_DOWN_BACKOFF_MILLIS / 1000, e.getMessage());
        }
    }
}
