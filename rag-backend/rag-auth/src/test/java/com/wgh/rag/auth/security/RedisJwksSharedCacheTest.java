package com.wgh.rag.auth.security;

import com.wgh.rag.config.redis.RedisCommandPort;
import com.wgh.rag.config.redis.RedisKeyNamespace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Redis JWKS 共享缓存：命名空间、TTL 与故障降级。
 */
@DisplayName("Redis JWKS 共享缓存")
class RedisJwksSharedCacheTest {

    private static final String JWKS_URI = "https://idp.example.com/.well-known/jwks.json";
    private static final String BODY = "{\"keys\":[]}";

    private final Map<String, String> store = new HashMap<>();
    private final Map<String, Duration> ttls = new HashMap<>();
    private OidcProperties properties;

    @BeforeEach
    void setUp() {
        properties = new OidcProperties();
        properties.setJwksSharedCacheEnabled(true);
    }

    @Test
    @DisplayName("写入 key 带命名空间前缀，TTL 与配置一致，可读回原文档")
    void writesNamespacedKeyWithTtl() {
        RedisJwksSharedCache cache = new RedisJwksSharedCache(workingRedis(), properties);

        cache.put(JWKS_URI, BODY, Duration.ofSeconds(300));

        String key = store.keySet().iterator().next();
        assertThat(key).startsWith(RedisKeyNamespace.root() + ":auth:jwks:");
        assertThat(store.get(key)).isEqualTo(BODY);
        assertThat(ttls.get(key)).isEqualTo(Duration.ofSeconds(300));
        assertThat(cache.get(JWKS_URI)).isEqualTo(BODY);
    }

    @Test
    @DisplayName("关闭开关时 enabled=false，读写均不落 Redis")
    void disabledSkipsRedis() {
        properties.setJwksSharedCacheEnabled(false);
        RedisJwksSharedCache cache = new RedisJwksSharedCache(workingRedis(), properties);

        cache.put(JWKS_URI, BODY, Duration.ofSeconds(300));

        assertThat(cache.enabled()).isFalse();
        assertThat(cache.get(JWKS_URI)).isNull();
        assertThat(store).isEmpty();
    }

    @Test
    @DisplayName("Redis 故障时读返回 null、写不抛异常（认证链路可降级）")
    void redisFailureIsSwallowed() {
        RedisJwksSharedCache cache = new RedisJwksSharedCache(failingRedis(), properties);

        assertThat(cache.get(JWKS_URI)).isNull();
        assertThatCode(() -> cache.put(JWKS_URI, BODY, Duration.ofSeconds(300)))
                .doesNotThrowAnyException();
    }

    private RedisCommandPort workingRedis() {
        return new FakeRedis(false);
    }

    private RedisCommandPort failingRedis() {
        return new FakeRedis(true);
    }

    private final class FakeRedis implements RedisCommandPort {

        private final boolean failing;

        private FakeRedis(boolean failing) {
            this.failing = failing;
        }

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public Long executeScript(String script, List<String> keys, List<String> args) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String get(String key) {
            if (failing) {
                throw new IllegalStateException("redis down");
            }
            return store.get(key);
        }

        @Override
        public void set(String key, String value, Duration ttl) {
            if (failing) {
                throw new IllegalStateException("redis down");
            }
            store.put(key, value);
            ttls.put(key, ttl);
        }

        @Override
        public void delete(String key) {
            store.remove(key);
        }
    }
}
