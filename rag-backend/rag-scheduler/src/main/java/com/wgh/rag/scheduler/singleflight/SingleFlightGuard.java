package com.wgh.rag.scheduler.singleflight;

import com.wgh.rag.config.redis.RedisDistributedLock;
import com.wgh.rag.config.redis.RedisUnavailableException;
import com.wgh.rag.scheduler.metrics.SchedulerMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 跨实例单飞：复用既有 {@link RedisDistributedLock}，严格失败关闭。
 *
 * <p>语义：
 * <ul>
 *   <li>Redis 未启用或命令失败 → 抛 {@link SingleFlightUnavailableException}，调用方放弃执行，
 *       <b>不降级为进程内锁</b>；</li>
 *   <li>锁已被其它实例持有 → 返回 {@code null}，调用方稍后再试；</li>
 *   <li>持锁期间由调度层按心跳续约，续约失败视为锁丢失。</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "wgh.scheduler", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SingleFlightGuard {

    private static final String KEY_PREFIX = "scheduler-singleflight:";

    private final RedisDistributedLock redisLock;

    /** 指标可为空（单元测试直接构造时不需要 Micrometer）。 */
    private final SchedulerMetrics metrics;

    /** 兼容仅需锁语义的调用方/测试。 */
    public SingleFlightGuard(RedisDistributedLock redisLock) {
        this(redisLock, null);
    }

    @Autowired
    public SingleFlightGuard(RedisDistributedLock redisLock, SchedulerMetrics metrics) {
        this.redisLock = redisLock;
        this.metrics = metrics;
    }

    /**
     * 尝试获取单飞锁。
     *
     * @param name 逻辑名称（如 {@code job:CONNECTOR_SYNC}）
     * @param ttl  锁 TTL
     * @return 持有句柄；已被其它实例持有时返回 {@code null}
     * @throws SingleFlightUnavailableException Redis 不可用，必须失败关闭
     */
    public Handle tryAcquire(String name, Duration ttl) {
        if (!redisLock.enabled()) {
            record("unavailable");
            throw new SingleFlightUnavailableException("Redis 未启用，拒绝降级为进程内单飞: " + name);
        }
        try {
            RedisDistributedLock.LockHandle handle = redisLock.tryAcquire(KEY_PREFIX + name, ttl);
            if (handle == null) {
                record("busy");
                return null;
            }
            record("acquired");
            return new Handle(handle, ttl);
        } catch (RedisUnavailableException e) {
            record("unavailable");
            throw new SingleFlightUnavailableException("Redis 不可用，无法获取单飞锁: " + name, e);
        }
    }

    /** 单飞锁持有句柄。 */
    public final class Handle implements AutoCloseable {

        private final RedisDistributedLock.LockHandle handle;

        private final Duration ttl;

        private Handle(RedisDistributedLock.LockHandle handle, Duration ttl) {
            this.handle = handle;
            this.ttl = ttl;
        }

        /** 续约；返回 {@code false} 表示锁已丢失（过期或被接管）。 */
        public boolean renew() {
            try {
                boolean renewed = redisLock.renew(handle, ttl);
                if (!renewed) {
                    record("renew_failed");
                    log.error("单飞锁续约失败（已过期或被接管）: {}", handle.name());
                }
                return renewed;
            } catch (RedisUnavailableException e) {
                record("renew_failed");
                log.error("单飞锁续约失败，按锁丢失处理: {}", handle.name(), e);
                return false;
            }
        }

        @Override
        public void close() {
            try {
                redisLock.unlock(handle);
            } catch (RedisUnavailableException e) {
                record("release_failed");
                log.warn("释放单飞锁失败（TTL 到期后自动释放）: {}", handle.name(), e);
            }
        }
    }

    private void record(String result) {
        if (metrics != null) {
            metrics.recordSingleFlight(result);
        }
    }
}
