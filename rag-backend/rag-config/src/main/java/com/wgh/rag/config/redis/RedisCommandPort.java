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
        };
    }
}
