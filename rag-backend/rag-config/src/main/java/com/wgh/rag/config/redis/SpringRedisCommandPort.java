package com.wgh.rag.config.redis;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于 Spring Data Redis（Lettuce）的命令实现。
 */
@Slf4j
public class SpringRedisCommandPort implements RedisCommandPort {

    /**
     * 批量追加 + 截断 + 续期，单次脚本原子完成。
     * ARGV 末尾两个参数分别是 maxSize 与 ttlSeconds，其余为待追加的值。
     */
    private static final String APPEND_BOUNDED_LIST =
            "local max = tonumber(ARGV[#ARGV - 1])\n"
            + "local ttl = tonumber(ARGV[#ARGV])\n"
            + "local n = 0\n"
            + "for i = 1, #ARGV - 2 do\n"
            + "  redis.call('RPUSH', KEYS[1], ARGV[i])\n"
            + "  n = n + 1\n"
            + "end\n"
            + "local len = redis.call('LLEN', KEYS[1])\n"
            + "if max > 0 and len > max then\n"
            + "  redis.call('LTRIM', KEYS[1], len - max, -1)\n"
            + "end\n"
            + "if ttl > 0 then\n"
            + "  redis.call('EXPIRE', KEYS[1], ttl)\n"
            + "end\n"
            + "return n\n";

    private final StringRedisTemplate template;
    private final boolean enabled;

    public SpringRedisCommandPort(StringRedisTemplate template, boolean enabled) {
        this.template = template;
        this.enabled = enabled;
    }

    @Override
    public boolean enabled() {
        return enabled && template != null;
    }

    @Override
    public Long executeScript(String script, List<String> keys, List<String> args) {
        if (!enabled()) {
            throw new RedisUnavailableException("Redis 未启用");
        }
        try {
            DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>(script, Long.class);
            return template.execute(redisScript, keys, args.toArray());
        } catch (RuntimeException e) {
            throw new RedisUnavailableException("Redis 命令执行失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String get(String key) {
        if (!enabled()) {
            throw new RedisUnavailableException("Redis 未启用");
        }
        try {
            return template.opsForValue().get(key);
        } catch (RuntimeException e) {
            throw new RedisUnavailableException("Redis 读取失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void set(String key, String value, Duration ttl) {
        if (!enabled()) {
            throw new RedisUnavailableException("Redis 未启用");
        }
        try {
            template.opsForValue().set(key, value, ttl);
        } catch (RuntimeException e) {
            throw new RedisUnavailableException("Redis 写入失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void delete(String key) {
        if (!enabled()) {
            throw new RedisUnavailableException("Redis 未启用");
        }
        try {
            template.delete(key);
        } catch (RuntimeException e) {
            throw new RedisUnavailableException("Redis 删除失败: " + e.getMessage(), e);
        }
    }

    @Override
    public Long appendBoundedList(String key, List<String> values, int maxSize, Duration ttl) {
        if (!enabled()) {
            throw new RedisUnavailableException("Redis 未启用");
        }
        if (values == null || values.isEmpty()) {
            return 0L;
        }
        List<String> args = new ArrayList<>(values.size() + 2);
        args.addAll(values);
        args.add(String.valueOf(Math.max(maxSize, 0)));
        args.add(String.valueOf(ttl == null ? 0 : Math.max(ttl.toSeconds(), 0)));
        try {
            DefaultRedisScript<Long> script = new DefaultRedisScript<>(APPEND_BOUNDED_LIST, Long.class);
            Long written = template.execute(script, List.of(key), args.toArray());
            return written == null ? 0L : written;
        } catch (RuntimeException e) {
            throw new RedisUnavailableException("Redis 列表写入失败: " + e.getMessage(), e);
        }
    }

    @Override
    public List<String> rangeList(String key, long start, long end) {
        if (!enabled()) {
            throw new RedisUnavailableException("Redis 未启用");
        }
        try {
            List<String> range = template.opsForList().range(key, start, end);
            return range == null ? List.of() : range;
        } catch (RuntimeException e) {
            throw new RedisUnavailableException("Redis 列表读取失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void expire(String key, Duration ttl) {
        if (!enabled()) {
            throw new RedisUnavailableException("Redis 未启用");
        }
        if (ttl == null) {
            return;
        }
        try {
            template.expire(key, ttl);
        } catch (RuntimeException e) {
            throw new RedisUnavailableException("Redis 过期设置失败: " + e.getMessage(), e);
        }
    }
}
