package com.wgh.rag.config.redis;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.List;

/**
 * 基于 Spring Data Redis（Lettuce）的命令实现。
 */
@Slf4j
public class SpringRedisCommandPort implements RedisCommandPort {

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
}
