package com.wgh.rag.config.redis;

import java.time.Duration;
import java.util.List;

/**
 * 对 Redis 原子命令的最小抽象。
 *
 * <p>业务侧只依赖该接口，便于在无 Docker / 无 Redis 的单元测试中注入桩实现，
 * 也便于在 Redis 不可用时统一降级。</p>
 */
public interface RedisCommandPort {

    /** Redis 是否可用（配置启用且底层客户端存在） */
    boolean enabled();

    /**
     * 执行 Lua 脚本并返回整数结果。
     *
     * @param script Lua 脚本内容
     * @param keys   KEYS
     * @param args   ARGV
     * @return 脚本返回值，脚本未返回值时返回 null
     * @throws RuntimeException Redis 不可用或命令失败
     */
    Long executeScript(String script, List<String> keys, List<String> args);

    /** 读取字符串值，不存在返回 null */
    String get(String key);

    /** 写入字符串并设置过期时间 */
    void set(String key, String value, Duration ttl);

    /** 删除 key */
    void delete(String key);

    /**
     * 向列表尾部批量追加字符串，并按上限截断头部、刷新过期时间。
     *
     * <p>整个操作在服务端原子完成（RPUSH + LLEN/LTRIM + EXPIRE），用于 SSE 事件重放缓冲：
     * 单个流的写入方只有生成实例一个，但批量写入仍需保证「写入」「截断」「续期」不可分割，
     * 避免重连方读到一半被截断的中间态。</p>
     *
     * @param key     列表 key
     * @param values  追加的字符串值（写入顺序即调用顺序）
     * @param maxSize 列表最大长度，≤0 表示不截断
     * @param ttl     列表过期时间，null 表示不续期
     * @return 实际写入条数
     * @throws RedisUnavailableException Redis 不可用或命令失败
     */
    default Long appendBoundedList(String key, List<String> values, int maxSize, Duration ttl) {
        throw new RedisUnavailableException("当前 Redis 实现不支持列表操作");
    }

    /**
     * 读取列表区间（Redis LRANGE 语义，-1 表示末尾）。
     *
     * @throws RedisUnavailableException Redis 不可用或命令失败
     */
    default List<String> rangeList(String key, long start, long end) {
        throw new RedisUnavailableException("当前 Redis 实现不支持列表操作");
    }

    /**
     * 设置 key 过期时间，用于流结束后把重放缓冲从「活跃 TTL」切换为「完成后 TTL」。
     *
     * @throws RedisUnavailableException Redis 不可用或命令失败
     */
    default void expire(String key, Duration ttl) {
        throw new RedisUnavailableException("当前 Redis 实现不支持过期设置");
    }

    /** 关闭状态的空实现 */
    static RedisCommandPort disabled() {
        return new RedisCommandPort() {
            @Override
            public boolean enabled() {
                return false;
            }

            @Override
            public Long executeScript(String script, List<String> keys, List<String> args) {
                throw new RedisUnavailableException("Redis 未启用");
            }

            @Override
            public String get(String key) {
                throw new RedisUnavailableException("Redis 未启用");
            }

            @Override
            public void set(String key, String value, Duration ttl) {
                throw new RedisUnavailableException("Redis 未启用");
            }

            @Override
            public void delete(String key) {
                throw new RedisUnavailableException("Redis 未启用");
            }

            @Override
            public Long appendBoundedList(String key, List<String> values, int maxSize, Duration ttl) {
                throw new RedisUnavailableException("Redis 未启用");
            }

            @Override
            public List<String> rangeList(String key, long start, long end) {
                throw new RedisUnavailableException("Redis 未启用");
            }

            @Override
            public void expire(String key, Duration ttl) {
                throw new RedisUnavailableException("Redis 未启用");
            }
        };
    }
}
