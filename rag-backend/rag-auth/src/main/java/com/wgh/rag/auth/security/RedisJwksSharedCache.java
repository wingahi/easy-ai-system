package com.wgh.rag.auth.security;

import com.wgh.rag.config.redis.RedisCommandPort;
import com.wgh.rag.config.redis.RedisKeyNamespace;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;

/**
 * 基于仓库既有 Redis 能力的 JWKS 共享缓存。
 * <p>
 * key 形如 {@code {wgh.rag.redis.key-prefix}:auth:jwks:<sha256(jwksUri)>}，与其它共享状态
 * 使用同一命名空间根，多环境共用 Redis 时可通过前缀隔离。存储原始 JWKS JSON 而非序列化的
 * 公钥对象，避免 JDK 序列化兼容问题。
 * <p>
 * 所有 Redis 异常都在本类内吞掉并返回未命中/忽略写入，保证缓存不可用时认证链路退化为
 * 本地缓存 + 回源，不会因 Redis 故障直接失败。
 */
@Slf4j
@Component
public class RedisJwksSharedCache implements JwksSharedCache {

    static final String KEY_MODULE = "auth:jwks";

    private final RedisCommandPort redis;

    private final OidcProperties properties;

    public RedisJwksSharedCache(RedisCommandPort redis, OidcProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    @Override
    public boolean enabled() {
        return properties.isJwksSharedCacheEnabled() && redis.enabled();
    }

    @Override
    public String get(String jwksUri) {
        if (!enabled() || !StringUtils.hasText(jwksUri)) {
            return null;
        }
        try {
            return redis.get(key(jwksUri));
        } catch (RuntimeException e) {
            log.debug("读取共享 JWKS 缓存失败，回退本地缓存：{}", e.getMessage());
            return null;
        }
    }

    @Override
    public void put(String jwksUri, String body, Duration ttl) {
        if (!enabled() || !StringUtils.hasText(jwksUri) || !StringUtils.hasText(body)
                || ttl == null || ttl.isZero() || ttl.isNegative()) {
            return;
        }
        try {
            redis.set(key(jwksUri), body, ttl);
        } catch (RuntimeException e) {
            log.debug("写入共享 JWKS 缓存失败，忽略：{}", e.getMessage());
        }
    }

    /** 共享缓存 key：命名空间根 + 模块 + JWKS 地址摘要。 */
    static String key(String jwksUri) {
        return RedisKeyNamespace.root() + ":" + KEY_MODULE + ":" + sha256Hex(jwksUri);
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            // 摘要仅用于生成稳定 key，算法不可用时退回 hashCode，仍保证可用
            return Integer.toHexString(value.hashCode());
        }
    }
}
