package com.wgh.rag.publicaccess.service;

import com.wgh.rag.config.redis.RedisKeyNamespace;

/**
 * 公开访问模块的 Redis key 命名空间。
 *
 * <p>统一以 {@code {root}:pa:} 开头（root 默认 {@code wgh}，可通过
 * {@code wgh.rag.redis.key-prefix} 配置），第二段区分用途：
 * {@code rl}=限流、{@code pwd}=分享密码、{@code embed}=Widget 授权。
 * 这样在多环境共用同一 Redis 时可按命名空间排查与清理。</p>
 */
public final class PublicAccessKeys {

    private PublicAccessKeys() {
    }

    /** 限流滑动窗口：{root}:pa:rl:{dimension}:{path}:{subject} */
    public static String rateLimit() {
        return RedisKeyNamespace.root() + ":pa:rl";
    }

    /** 分享密码失败计数/锁定：{root}:pa:pwd:{fail|lock}:{token}:{ip} */
    public static String sharePassword() {
        return RedisKeyNamespace.root() + ":pa:pwd";
    }

    /** Widget 嵌入授权：{root}:pa:embed:grant:{token}:{grant} */
    public static String embedGrant() {
        return RedisKeyNamespace.root() + ":pa:embed:grant";
    }

    /** 清除非命名空间安全字符，避免 key 中出现空白/控制字符 */
    public static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        return value.trim().replaceAll("[^A-Za-z0-9._:/-]", "_");
    }
}
