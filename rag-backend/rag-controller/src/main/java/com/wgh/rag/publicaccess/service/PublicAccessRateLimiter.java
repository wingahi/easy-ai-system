package com.wgh.rag.publicaccess.service;

import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.result.ResultCode;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 轻量内存限流器（固定 60 秒滑动窗口）。
 * <p>
 * 简化实现：单机内存计数，多实例部署时各实例独立计数；生产环境可替换为 Redis 计数。
 */
@Component
public class PublicAccessRateLimiter {

    private static final long WINDOW_MS = 60_000L;
    private static final int MAX_KEYS = 20_000;

    private final Map<String, Deque<Long>> windows = new ConcurrentHashMap<>();

    /**
     * 检查并消费一次配额，超限抛 429 业务异常
     */
    public void acquire(String key, int limitPerMinute) {
        if (limitPerMinute <= 0) return;
        long now = System.currentTimeMillis();
        Deque<Long> q = windows.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() >= WINDOW_MS) {
                q.pollFirst();
            }
            if (q.size() >= limitPerMinute) {
                throw new BusinessException(ResultCode.QUOTA_EXCEEDED,
                        "请求过于频繁（每分钟上限 " + limitPerMinute + " 次），请稍后再试");
            }
            q.addLast(now);
        }
        if (windows.size() > MAX_KEYS) {
            windows.entrySet().removeIf(e -> {
                Deque<Long> d = e.getValue();
                synchronized (d) {
                    return d.isEmpty() || now - d.peekLast() >= WINDOW_MS;
                }
            });
        }
    }
}
