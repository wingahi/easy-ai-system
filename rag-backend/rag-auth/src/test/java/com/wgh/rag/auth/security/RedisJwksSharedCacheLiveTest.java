package com.wgh.rag.auth.security;

import com.wgh.rag.config.redis.RedisKeyNamespace;
import com.wgh.rag.config.redis.SpringRedisCommandPort;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 本机 Redis 实测：两个共享缓存实例复用同一份 JWKS 文档，key 带命名空间前缀且带 TTL。
 * <p>Redis 不可达时通过 {@link Assumptions} 跳过，不影响常规构建。</p>
 */
@DisplayName("Redis JWKS 共享缓存（本机实测）")
class RedisJwksSharedCacheLiveTest {

    private static final String PREFIX = "wgh-itest-jwks-" + System.currentTimeMillis();
    private static final String JWKS_URI = "https://itest-idp.example.com/jwks.json";
    private static final String BODY = "{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"itest\"}]}";

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate template;
    private static SpringRedisCommandPort redis;
    private static String previousRoot;

    @BeforeAll
    static void setUp() {
        String host = System.getProperty("wgh.itest.redis.host", "127.0.0.1");
        int port = Integer.getInteger("wgh.itest.redis.port", 6379);
        factory = new LettuceConnectionFactory(host, port);
        factory.afterPropertiesSet();
        factory.start();
        template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        redis = new SpringRedisCommandPort(template, true);

        boolean reachable;
        try {
            reachable = "PONG".equalsIgnoreCase(template.getConnectionFactory().getConnection().ping());
        } catch (RuntimeException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable, "本机 Redis 不可达，跳过实测");
        previousRoot = RedisKeyNamespace.root();
        RedisKeyNamespace.configure(PREFIX);
    }

    @AfterAll
    static void tearDown() {
        if (template != null) {
            // 先按测试前缀删除，再还原全局命名空间，避免 key 计算漂移
            template.delete(RedisJwksSharedCache.key(JWKS_URI));
        }
        if (previousRoot != null) {
            RedisKeyNamespace.configure(previousRoot);
        }
        if (factory != null) {
            factory.destroy();
        }
    }

    @Test
    @DisplayName("实例 A 写入后实例 B 可直接读取，key 带前缀且 TTL 生效")
    void sharedBetweenInstances() {
        OidcProperties properties = new OidcProperties();
        properties.setJwksSharedCacheEnabled(true);
        RedisJwksSharedCache instanceA = new RedisJwksSharedCache(redis, properties);
        RedisJwksSharedCache instanceB = new RedisJwksSharedCache(redis, properties);

        instanceA.put(JWKS_URI, BODY, Duration.ofSeconds(120));

        String key = RedisJwksSharedCache.key(JWKS_URI);
        assertThat(key).startsWith(PREFIX + ":auth:jwks:");
        assertThat(instanceB.get(JWKS_URI)).isEqualTo(BODY);
        assertThat(template.getExpire(key, TimeUnit.SECONDS)).isBetween(1L, 120L);
    }
}
