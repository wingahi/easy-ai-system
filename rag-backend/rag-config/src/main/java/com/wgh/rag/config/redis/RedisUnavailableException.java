package com.wgh.rag.config.redis;

/**
 * Redis 不可用（未启用、连接失败、命令超时等）。
 */
public class RedisUnavailableException extends RuntimeException {

    public RedisUnavailableException(String message) {
        super(message);
    }

    public RedisUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
