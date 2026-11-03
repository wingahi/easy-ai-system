package com.wgh.rag.publicaccess.service;

import com.wgh.rag.config.redis.RedisCommandPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Redis 优先、单机内存兜底的公开访问存储实现。
 *
 * <p>限流与计数均通过 Lua 脚本在 Redis 端原子执行；Redis 不可用时自动回退到
 * 进程内实现并输出 WARN 日志。降级期间不同实例各自计数，属于已知限制。</p>
 */
@Slf4j
@Component
public class DefaultPublicAccessStore implements PublicAccessStore {

    /** 滑动窗口：清理过期成员 -> 判断是否超限 -> 写入当前请求。整个过程在 Redis 单线程内原子执行 */
    private static final String SLIDING_WINDOW_LUA = """
            local key = KEYS[1]
            local now = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            local limit = tonumber(ARGV[3])
            local member = ARGV[4]
            redis.call('ZREMRANGEBYSCORE', key, '-inf', now - window)
            local count = redis.call('ZCARD', key)
            if count >= limit then
              redis.call('PEXPIRE', key, window)
              return -1
            end
            redis.call('ZADD', key, now, member)
            redis.call('PEXPIRE', key, window)
            return limit - count - 1
            """;

    /** 原子自增并刷新过期时间 */
    private static final String INCREMENT_LUA = """
            local count = redis.call('INCR', KEYS[1])
            redis.call('PEXPIRE', KEYS[1], ARGV[1])
            return count
            """;

    /** Redis 失败后暂停访问的时间，避免每个请求都等待超时 */
    private static final long REDIS_DOWN_BACKOFF_MILLIS = 10_000L;
    private static final long WARN_INTERVAL_MILLIS = 30_000L;

    private final RedisCommandPort redis;
    private final Map<String, Deque<Long>> slidingWindows = new ConcurrentHashMap<>();
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    private volatile long redisDownUntil;
    private volatile long lastWarnAt;
    private volatile boolean disabledWarned;

    public DefaultPublicAccessStore(RedisCommandPort redis) {
        this.redis = redis;
    }

    @Override
    public boolean distributed() {
        return redis.enabled() && System.currentTimeMillis() >= redisDownUntil;
    }

    @Override
    public long acquireSliding(String key, long nowMillis, long windowMillis, int limit) {
        if (limit <= 0) {
            return 0;
        }
        if (redisUsable(nowMillis)) {
            try {
                String member = nowMillis + ":" + ThreadLocalRandom.current().nextLong();
                Long remaining = redis.executeScript(SLIDING_WINDOW_LUA,
                        List.of(key),
                        List.of(String.valueOf(nowMillis), String.valueOf(windowMillis), String.valueOf(limit), member));
                if (remaining != null) {
                    return remaining;
                }
            } catch (RuntimeException e) {
                markRedisDown(nowMillis, e);
            }
        }
        return acquireSlidingInMemory(key, nowMillis, windowMillis, limit);
    }

    @Override
    public long increment(String key, long nowMillis, Duration ttl) {
        long ttlMillis = Math.max(ttl.toMillis(), 1L);
        if (redisUsable(nowMillis)) {
            try {
                Long value = redis.executeScript(INCREMENT_LUA,
                        List.of(key),
                        List.of(String.valueOf(ttlMillis)));
                if (value != null) {
                    // 同步内存计数，Redis 短暂故障时保持连续
                    entries.put(key, new Entry(String.valueOf(value), nowMillis + ttlMillis));
                    return value;
                }
            } catch (RuntimeException e) {
                markRedisDown(nowMillis, e);
            }
        }
        Entry entry = entries.get(key);
        long next = 1L;
        if (entry != null && entry.expiresAt > nowMillis) {
            try {
                next = Long.parseLong(entry.value) + 1;
            } catch (NumberFormatException ignored) {
                next = 1L;
            }
        }
        entries.put(key, new Entry(String.valueOf(next), nowMillis + ttlMillis));
        cleanup(nowMillis);
        return next;
    }

    @Override
    public Optional<Long> getNumber(String key, long nowMillis) {
        Optional<String> value = getString(key, nowMillis);
        if (value.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(value.get()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<String> getString(String key, long nowMillis) {
        if (redisUsable(nowMillis)) {
            try {
                String value = redis.get(key);
                if (value != null) {
                    return Optional.of(value);
                }
                // Redis 可访问且 key 不存在时以 Redis 为准，避免外部清理（如运维删除锁定键）后
                // 仍读到进程内镜像，导致多实例行为不一致
                entries.remove(key);
                return Optional.empty();
            } catch (RuntimeException e) {
                markRedisDown(nowMillis, e);
            }
        }
        Entry entry = entries.get(key);
        if (entry == null || entry.expiresAt <= nowMillis) {
            if (entry != null) {
                entries.remove(key);
            }
            return Optional.empty();
        }
        return Optional.of(entry.value);
    }

    @Override
    public void setString(String key, String value, long nowMillis, Duration ttl) {
        long ttlMillis = Math.max(ttl.toMillis(), 1L);
        if (redisUsable(nowMillis)) {
            try {
                redis.set(key, value, Duration.ofMillis(ttlMillis));
                entries.put(key, new Entry(value, nowMillis + ttlMillis));
                return;
            } catch (RuntimeException e) {
                markRedisDown(nowMillis, e);
            }
        }
        entries.put(key, new Entry(value, nowMillis + ttlMillis));
        cleanup(nowMillis);
    }

    @Override
    public void delete(String key) {
        entries.remove(key);
        if (redis.enabled()) {
            try {
                redis.delete(key);
            } catch (RuntimeException e) {
                markRedisDown(System.currentTimeMillis(), e);
            }
        }
    }

    private long acquireSlidingInMemory(String key, long nowMillis, long windowMillis, int limit) {
        Deque<Long> window = slidingWindows.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (window) {
            long threshold = nowMillis - windowMillis;
            while (!window.isEmpty() && window.peekFirst() <= threshold) {
                window.removeFirst();
            }
            if (window.size() >= limit) {
                return -1;
            }
            window.addLast(nowMillis);
            return limit - window.size();
        }
    }

    private boolean redisUsable(long nowMillis) {
        if (!redis.enabled()) {
            if (!disabledWarned) {
                disabledWarned = true;
                log.warn("Redis 未启用，公开访问限流/密码锁定降级为单机内存模式");
            }
            return false;
        }
        return nowMillis >= redisDownUntil;
    }

    private void markRedisDown(long nowMillis, RuntimeException e) {
        redisDownUntil = nowMillis + REDIS_DOWN_BACKOFF_MILLIS;
        long now = System.currentTimeMillis();
        if (now - lastWarnAt >= WARN_INTERVAL_MILLIS) {
            lastWarnAt = now;
            log.warn("Redis 不可用，公开访问限流/密码锁定降级为单机内存模式，{} 秒内不再重试 Redis: {}",
                    REDIS_DOWN_BACKOFF_MILLIS / 1000, e.getMessage());
        }
    }

    private void cleanup(long nowMillis) {
        if (entries.size() < 1024) {
            return;
        }
        List<String> expired = new ArrayList<>();
        for (Map.Entry<String, Entry> item : entries.entrySet()) {
            if (item.getValue().expiresAt <= nowMillis) {
                expired.add(item.getKey());
            }
        }
        expired.forEach(entries::remove);
        slidingWindows.entrySet().removeIf(item -> {
            Deque<Long> window = item.getValue();
            synchronized (window) {
                return window.isEmpty();
            }
        });
    }

    private record Entry(String value, long expiresAt) {
    }
}
