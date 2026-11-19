package com.wgh.rag.config.redis;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 可复用的 Redis 分布式锁。
 *
 * <p>语义：</p>
 * <ul>
 *   <li>加锁：{@code SET key owner NX PX ttl}，owner 为本次持有者的唯一标识；</li>
 *   <li>解锁：Lua 先比较 value 是否等于 owner，相等才删除，避免误删他实例已续约/重新获取的锁；</li>
 *   <li>续约：Lua 先比较 owner，相等才执行 {@code PEXPIRE}，用于跨 TTL 的长任务；</li>
 *   <li>过期即视为可抢：持有者进程消失后，锁在 TTL 到期后自动释放，其他实例可重新获取。
 *       因此长任务需要周期性 {@link #renew(LockHandle)}，否则超过 TTL 后存在被其他实例抢占的可能。</li>
 * </ul>
 *
 * <p>Redis 未启用或命令失败时抛出 {@link RedisUnavailableException}，由调用方决定如何处置。
 * 跨实例互斥场景（如任务恢复）必须直接拒绝，不允许退回进程内锁——进程内锁无法约束其他实例。</p>
 */
@Slf4j
@Component
public class RedisDistributedLock {

    /** 默认租期：60s。长任务应通过 {@link #renew(LockHandle)} 续约。 */
    public static final Duration DEFAULT_TTL = Duration.ofSeconds(60);

    /** 默认等待时间：0，即只尝试一次。 */
    public static final Duration DEFAULT_WAIT = Duration.ZERO;

    private static final long RETRY_INTERVAL_MS = 100L;

    /** SET NX PX：成功返回 1，已被占用返回 0。 */
    private static final String ACQUIRE_SCRIPT = """
            if redis.call('set', KEYS[1], ARGV[1], 'NX', 'PX', ARGV[2]) then
                return 1
            end
            return 0
            """;

    /** 只有持有者才能续期：返回 1 表示续期成功，0 表示不是持有者或已过期。 */
    private static final String RENEW_SCRIPT = """
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('pexpire', KEYS[1], ARGV[2])
            end
            return 0
            """;

    /** 只有持有者才能删除：返回 1 表示删除成功，0 表示不是持有者或已过期。 */
    private static final String RELEASE_SCRIPT = """
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('del', KEYS[1])
            end
            return 0
            """;

    private final RedisCommandPort commandPort;

    public RedisDistributedLock(RedisCommandPort commandPort) {
        this.commandPort = commandPort;
    }

    /** Redis 是否可用（配置启用且底层客户端存在）。 */
    public boolean enabled() {
        return commandPort.enabled();
    }

    /** 逻辑名称 → 带命名空间前缀的完整 key：{@code {root}:lock:{name}}。 */
    public String keyOf(String name) {
        return RedisKeyNamespace.root() + ":lock:" + sanitize(name);
    }

    /** 使用默认租期、只尝试一次。 */
    public LockHandle tryAcquire(String name) {
        return tryAcquire(name, DEFAULT_TTL, DEFAULT_WAIT);
    }

    /** 指定租期、只尝试一次。 */
    public LockHandle tryAcquire(String name, Duration ttl) {
        return tryAcquire(name, ttl, DEFAULT_WAIT);
    }

    /**
     * 尝试加锁。
     *
     * @param name     逻辑名称（如 {@code task-resume:AGENT:64}）
     * @param ttl      租期，必须大于 0
     * @param waitTime 最长等待时间，0 表示只尝试一次
     * @return 成功返回持有凭证；未获取到返回 {@code null}
     * @throws RedisUnavailableException Redis 未启用或命令失败
     */
    public LockHandle tryAcquire(String name, Duration ttl, Duration waitTime) {
        if (!enabled()) {
            throw new RedisUnavailableException("Redis 未启用，无法加锁: " + name);
        }
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl 必须大于 0");
        }
        Duration wait = waitTime == null ? Duration.ZERO : waitTime;
        String key = keyOf(name);
        String owner = UUID.randomUUID().toString();
        long ttlMillis = Math.max(1L, ttl.toMillis());
        long waitMillis = Math.max(0L, wait.toMillis());
        long deadline = System.currentTimeMillis() + waitMillis;

        while (true) {
            Long result = commandPort.executeScript(ACQUIRE_SCRIPT, List.of(key),
                    List.of(owner, String.valueOf(ttlMillis)));
            if (result != null && result == 1L) {
                return new LockHandle(key, name, owner, ttl);
            }
            long remaining = deadline - System.currentTimeMillis();
            if (waitMillis <= 0 || remaining <= 0) {
                return null;
            }
            try {
                Thread.sleep(Math.min(RETRY_INTERVAL_MS, remaining));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }

    /** 按持有凭证原租期续约。 */
    public boolean renew(LockHandle handle) {
        return handle != null && renew(handle, handle.ttl());
    }

    /**
     * 续约；只有当前 value 等于持有者标识时才会刷新过期时间。
     *
     * @return true 表示续约成功；false 表示锁已过期或已被其他实例持有
     * @throws RedisUnavailableException Redis 未启用或命令失败
     */
    public boolean renew(LockHandle handle, Duration ttl) {
        if (handle == null) return false;
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl 必须大于 0");
        }
        if (!enabled()) {
            throw new RedisUnavailableException("Redis 未启用，无法续约: " + handle.name());
        }
        Long result = commandPort.executeScript(RENEW_SCRIPT, List.of(handle.key()),
                List.of(handle.owner(), String.valueOf(Math.max(1L, ttl.toMillis()))));
        return result != null && result == 1L;
    }

    /**
     * 释放锁；通过 Lua 原子校验持有者，避免误删其他实例的锁。
     *
     * @return true 表示由本次持有者删除成功；false 表示锁已过期或已不属于该持有者
     * @throws RedisUnavailableException Redis 未启用或命令失败
     */
    public boolean unlock(LockHandle handle) {
        if (handle == null) return false;
        if (!enabled()) {
            throw new RedisUnavailableException("Redis 未启用，无法解锁: " + handle.name());
        }
        Long result = commandPort.executeScript(RELEASE_SCRIPT, List.of(handle.key()),
                List.of(handle.owner()));
        return result != null && result == 1L;
    }

    private String sanitize(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("锁名称不能为空");
        }
        String sanitized = name.trim().replaceAll("[^A-Za-z0-9._:-]", "-");
        return sanitized.startsWith(":") ? sanitized.substring(1) : sanitized;
    }

    /** 锁持有凭证。 */
    public record LockHandle(String key, String name, String owner, Duration ttl) {
    }
}
