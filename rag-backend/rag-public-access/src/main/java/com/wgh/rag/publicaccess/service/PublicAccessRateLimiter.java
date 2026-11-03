package com.wgh.rag.publicaccess.service;

import com.wgh.rag.common.metrics.RagMetrics;
import com.wgh.rag.common.result.ResultCode;
import com.wgh.rag.publicaccess.config.PublicAccessProperties;
import com.wgh.rag.publicaccess.exception.PublicAccessException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 公开访问分布式限流器（60 秒滑动窗口）。
 *
 * <<p>Redis 模式下通过 Lua 脚本原子完成「清理过期成员 + 计数 + 写入」，
 * 避免并发请求同时读到未超限计数导致超发；Redis 不可用时由
 * {@link DefaultPublicAccessStore} 自动降级为单机内存滑动窗口。</p>
 */
@Component
public class PublicAccessRateLimiter {

    private static final long WINDOW_MS = 60_000L;

    private final PublicAccessStore store;
    private final PublicAccessProperties properties;
    private final RagMetrics metrics;

    public PublicAccessRateLimiter(PublicAccessStore store, PublicAccessProperties properties,
                                   RagMetrics metrics) {
        this.store = store;
        this.properties = properties;
        this.metrics = metrics;
    }

    /**
     * 按维度 + 主体 + 接口路径限流，上限由配置覆盖规则决定。
     *
     * @param dimension 维度，例如 share-token / share-ip / api-key / api-ip
     * @param subject   主体，例如 token、IP、API Key ID
     * @param path      接口路径
     */
    public void acquire(String dimension, String subject, String path) {
        acquire(dimension, subject, path, properties.limitFor(dimension, path));
    }

    public void acquire(String dimension, String subject, String path, int limitPerMinute) {
        metrics.recordPublicAccess(dimension);
        if (limitPerMinute <= 0) {
            return;
        }
        String key = buildKey(dimension, subject, path);
        long remaining = store.acquireSliding(key, System.currentTimeMillis(), WINDOW_MS, limitPerMinute);
        if (remaining < 0) {
            metrics.recordRateLimitRejected(dimension);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("dimension", dimension);
            data.put("limitPerMinute", limitPerMinute);
            data.put("retryAfterSeconds", WINDOW_MS / 1000);
            data.put("store", store.distributed() ? "redis" : "memory");
            throw new PublicAccessException(ResultCode.QUOTA_EXCEEDED,
                    "请求过于频繁（" + dimension + " 每分钟上限 " + limitPerMinute + " 次），请 "
                            + (WINDOW_MS / 1000) + " 秒后重试", data);
        }
    }

    /** 是否运行在 Redis 分布式限流模式 */
    public boolean distributed() {
        return store.distributed();
    }

    static String buildKey(String dimension, String subject, String path) {
        return "wgh:pa:rl:" + sanitize(dimension) + ":" + sanitize(path) + ":" + sanitize(subject);
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        return value.trim().replaceAll("[^A-Za-z0-9._:/-]", "_");
    }
}
