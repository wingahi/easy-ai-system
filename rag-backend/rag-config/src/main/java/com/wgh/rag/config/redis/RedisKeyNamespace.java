package com.wgh.rag.config.redis;

/**
 * 共享状态 Redis key 的命名空间根。
 *
 * <p>由 {@link RagRedisProperties} 在启动时注入 {@code wgh.rag.redis.key-prefix}，
 * 默认 {@code wgh}。业务代码按 {@code {root}:{module}:...} 组装 key，
 * 多环境共用同一个 Redis 实例时可通过配置区分，避免互相覆盖限流/锁定计数。</p>
 */
public final class RedisKeyNamespace {

    private static volatile String root = "wgh";

    private RedisKeyNamespace() {
    }

    /** 启动时由配置属性调用；空值回退默认根 */
    public static void configure(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            root = "wgh";
            return;
        }
        root = prefix.trim().replaceAll("[^A-Za-z0-9._:-]", "-");
    }

    public static String root() {
        return root;
    }
}
