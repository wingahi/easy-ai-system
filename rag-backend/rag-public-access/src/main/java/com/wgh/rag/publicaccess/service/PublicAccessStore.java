package com.wgh.rag.publicaccess.service;

import java.time.Duration;
import java.util.Optional;

/**
 * 公开访问模块的分布式存储抽象（限流、密码失败计数、嵌入授权 grant）。
 *
 * <p>实现必须在 Redis 不可用时自行降级到单机内存，调用方无需感知。</p>
 */
public interface PublicAccessStore {

    /** 当前是否使用 Redis 分布式存储（仅表示配置可用，不代表单次命令成功） */
    boolean distributed();

    /**
     * 原子滑动窗口限流。
     *
     * @return 放行时返回剩余可用次数（&gt;=0）；超限返回 -1
     */
    long acquireSliding(String key, long nowMillis, long windowMillis, int limit);

    /**
     * 原子自增并刷新过期时间。
     */
    long increment(String key, long nowMillis, Duration ttl);

    Optional<Long> getNumber(String key, long nowMillis);

    Optional<String> getString(String key, long nowMillis);

    void setString(String key, String value, long nowMillis, Duration ttl);

    void delete(String key);
}
