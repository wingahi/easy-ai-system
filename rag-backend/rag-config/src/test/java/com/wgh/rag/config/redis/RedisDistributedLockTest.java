package com.wgh.rag.config.redis;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分布式锁并发语义测试，使用内存 FakeRedis 模拟 SET NX PX / PEXPIRE / DEL 的原子行为。
 */
class RedisDistributedLockTest {

    @Test
    void keyUsesNamespacePrefix() {
        RedisDistributedLock lock = new RedisDistributedLock(new FakeRedisPort());
        assertEquals("wgh:lock:task-resume:AGENT:1", lock.keyOf("task-resume:AGENT:1"));
    }

    @Test
    void concurrentAcquireAllowsOnlyOneOwner() throws Exception {
        RedisDistributedLock lock = new RedisDistributedLock(new FakeRedisPort());
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<RedisDistributedLock.LockHandle>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return lock.tryAcquire("task-resume:AGENT:1");
            }));
        }
        start.countDown();

        int acquired = 0;
        for (Future<RedisDistributedLock.LockHandle> f : futures) {
            if (f.get(5, TimeUnit.SECONDS) != null) acquired++;
        }
        pool.shutdownNow();
        assertEquals(1, acquired);
    }

    @Test
    void nonOwnerCannotReleaseOrRenew() {
        FakeRedisPort port = new FakeRedisPort();
        RedisDistributedLock lock = new RedisDistributedLock(port);
        RedisDistributedLock.LockHandle owner = lock.tryAcquire("task-resume:AGENT:2");
        assertNotNull(owner);

        RedisDistributedLock.LockHandle forged = new RedisDistributedLock.LockHandle(
                owner.key(), owner.name(), "other-owner", owner.ttl());
        assertFalse(lock.unlock(forged));
        assertFalse(lock.renew(forged, Duration.ofSeconds(5)));
        assertNull(lock.tryAcquire("task-resume:AGENT:2"));

        assertTrue(lock.unlock(owner));
        assertNotNull(lock.tryAcquire("task-resume:AGENT:2"));
    }

    @Test
    void renewExtendsTtlOnlyForOwner() {
        FakeRedisPort port = new FakeRedisPort();
        RedisDistributedLock lock = new RedisDistributedLock(port);
        RedisDistributedLock.LockHandle owner = lock.tryAcquire("task-resume:WORKFLOW:3", Duration.ofMillis(1000));
        assertNotNull(owner);

        port.advance(800);
        assertTrue(lock.renew(owner, Duration.ofMillis(1000)));
        port.advance(800);
        // 原 TTL 已过期，但续约后仍未过期
        assertNull(lock.tryAcquire("task-resume:WORKFLOW:3"));

        port.advance(1200);
        // 续约后的 TTL 也过期，可被重新抢占
        assertNotNull(lock.tryAcquire("task-resume:WORKFLOW:3"));
    }

    @Test
    void expiredLockCanBeAcquiredAndOldOwnerCannotRelease() {
        FakeRedisPort port = new FakeRedisPort();
        RedisDistributedLock lock = new RedisDistributedLock(port);
        RedisDistributedLock.LockHandle oldOwner = lock.tryAcquire("task-resume:MULTI_AGENT:4", Duration.ofMillis(500));
        assertNotNull(oldOwner);

        port.advance(501);
        RedisDistributedLock.LockHandle newOwner = lock.tryAcquire("task-resume:MULTI_AGENT:4", Duration.ofMillis(500));
        assertNotNull(newOwner);
        assertFalse(lock.unlock(oldOwner));
        assertNull(lock.tryAcquire("task-resume:MULTI_AGENT:4"));
        assertTrue(lock.unlock(newOwner));
    }

    @Test
    void waiterAcquiresAfterRelease() throws Exception {
        FakeRedisPort port = new FakeRedisPort();
        RedisDistributedLock lock = new RedisDistributedLock(port);
        RedisDistributedLock.LockHandle first = lock.tryAcquire("task-resume:AGENT:5", Duration.ofSeconds(30));
        assertNotNull(first);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<RedisDistributedLock.LockHandle> waiter = pool.submit(
                () -> lock.tryAcquire("task-resume:AGENT:5", Duration.ofSeconds(30), Duration.ofSeconds(2)));
        Thread.sleep(200);
        assertTrue(lock.unlock(first));

        assertNotNull(waiter.get(3, TimeUnit.SECONDS));
        pool.shutdownNow();
    }

    @Test
    void disabledRedisThrowsForCallerToDegrade() {
        FakeRedisPort port = new FakeRedisPort();
        port.enabled = false;
        RedisDistributedLock lock = new RedisDistributedLock(port);
        RedisDistributedLock.LockHandle handle = new RedisDistributedLock.LockHandle(
                "wgh:lock:x", "x", "owner", Duration.ofSeconds(30));

        assertThrows(RedisUnavailableException.class, () -> lock.tryAcquire("task-resume:AGENT:6"));
        assertThrows(RedisUnavailableException.class, () -> lock.renew(handle));
        assertThrows(RedisUnavailableException.class, () -> lock.unlock(handle));
    }

    /** 用内存 Map + 可控时钟模拟 Redis 的原子命令语义。 */
    private static final class FakeRedisPort implements RedisCommandPort {

        private final Map<String, Entry> store = new HashMap<>();
        private volatile boolean enabled = true;
        private long now = 0L;

        synchronized void advance(long millis) {
            now += millis;
        }

        @Override
        public boolean enabled() {
            return enabled;
        }

        @Override
        public synchronized Long executeScript(String script, List<String> keys, List<String> args) {
            if (!enabled) throw new RedisUnavailableException("disabled");
            String key = keys.get(0);
            String owner = args.get(0);
            if (script.contains("'set'")) {
                long ttl = Long.parseLong(args.get(1));
                Entry current = store.get(key);
                if (current != null && !current.expired(now)) return 0L;
                store.put(key, new Entry(owner, now + ttl));
                return 1L;
            }
            if (script.contains("'pexpire'")) {
                long ttl = Long.parseLong(args.get(1));
                Entry current = store.get(key);
                if (current == null || current.expired(now) || !current.owner.equals(owner)) return 0L;
                current.expireAt = now + ttl;
                return 1L;
            }
            if (script.contains("'del'")) {
                Entry current = store.get(key);
                if (current == null || current.expired(now) || !current.owner.equals(owner)) return 0L;
                store.remove(key);
                return 1L;
            }
            throw new IllegalArgumentException("unknown script");
        }

        @Override
        public synchronized String get(String key) {
            if (!enabled) throw new RedisUnavailableException("disabled");
            Entry current = store.get(key);
            return current == null || current.expired(now) ? null : current.owner;
        }

        @Override
        public synchronized void set(String key, String value, Duration ttl) {
            if (!enabled) throw new RedisUnavailableException("disabled");
            store.put(key, new Entry(value, now + ttl.toMillis()));
        }

        @Override
        public synchronized void delete(String key) {
            if (!enabled) throw new RedisUnavailableException("disabled");
            store.remove(key);
        }

        private static final class Entry {
            private final String owner;
            private long expireAt;

            private Entry(String owner, long expireAt) {
                this.owner = owner;
                this.expireAt = expireAt;
            }

            private boolean expired(long now) {
                return now >= expireAt;
            }
        }
    }
}
