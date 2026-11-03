package com.wgh.rag.publicaccess.service;

import com.wgh.rag.common.result.ResultCode;
import com.wgh.rag.publicaccess.config.PublicAccessProperties;
import com.wgh.rag.publicaccess.entity.ShareLink;
import com.wgh.rag.publicaccess.exception.PublicAccessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * 分享密码失败计数与锁定。
 *
 * <p>计数 key 为「分享 token + 客户端 IP」，存 Redis 并带过期时间；
 * Redis 不可用时自动回退到单机内存。密码错误达到阈值后写入锁定 key，
 * 锁定期间即使密码正确也拒绝，并返回剩余等待秒数。</p>
 */
@Component
public class SharePasswordGuard {

    private static final String KEY_PREFIX = "wgh:pa:pwd:";

    private final PublicAccessStore store;
    private final PublicAccessProperties properties;
    private final LongSupplier clock;

    @Autowired
    public SharePasswordGuard(PublicAccessStore store, PublicAccessProperties properties) {
        this(store, properties, System::currentTimeMillis);
    }

    public SharePasswordGuard(PublicAccessStore store, PublicAccessProperties properties, LongSupplier clock) {
        this.store = store;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 剩余锁定秒数；0 表示未锁定。
     */
    public long lockRemainingSeconds(String token, String clientIp) {
        if (token == null || clientIp == null || clientIp.isBlank()) {
            return 0;
        }
        long now = clock.getAsLong();
        Optional<String> expireAt = store.getString(lockKey(token, clientIp), now);
        if (expireAt.isEmpty()) {
            return 0;
        }
        long remainingMillis;
        try {
            remainingMillis = Long.parseLong(expireAt.get()) - now;
        } catch (NumberFormatException e) {
            store.delete(lockKey(token, clientIp));
            return 0;
        }
        if (remainingMillis <= 0) {
            store.delete(lockKey(token, clientIp));
            return 0;
        }
        return (remainingMillis + 999) / 1000;
    }

    /**
     * 记录一次密码错误；达到阈值时写入锁定并抛出锁定异常，否则抛出带剩余次数的密码错误异常。
     */
    public void onFailure(ShareLink link, String clientIp) {
        long now = clock.getAsLong();
        int maxFailures = Math.max(1, properties.getSharePassword().getMaxFailures());
        int lockMinutes = Math.max(1, properties.getSharePassword().getLockMinutes());
        Duration ttl = Duration.ofMinutes(lockMinutes);
        long count = store.increment(failKey(link.getToken(), clientIp), now, ttl);
        if (count >= maxFailures) {
            long expireAt = now + ttl.toMillis();
            store.setString(lockKey(link.getToken(), clientIp), String.valueOf(expireAt), now, ttl);
            throw lockedException(ttl.toSeconds());
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("locked", false);
        data.put("remainingAttempts", maxFailures - count);
        data.put("maxFailures", maxFailures);
        throw new PublicAccessException(ResultCode.PERMISSION_DENIED,
                "访问密码错误，还可尝试 " + (maxFailures - count) + " 次", data);
    }

    /**
     * 验证成功后按配置清零失败计数。
     */
    public void onSuccess(ShareLink link, String clientIp) {
        if (!properties.getSharePassword().isResetOnSuccess()) {
            return;
        }
        store.delete(failKey(link.getToken(), clientIp));
        store.delete(lockKey(link.getToken(), clientIp));
    }

    public PublicAccessException lockedException(long lockRemainingSeconds) {
        long seconds = Math.max(1L, lockRemainingSeconds);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("locked", true);
        data.put("remainingAttempts", 0);
        data.put("lockRemainingSeconds", seconds);
        data.put("lockMinutes", (seconds + 59) / 60);
        return new PublicAccessException(ResultCode.PUBLIC_ACCESS_LOCKED,
                "访问密码错误次数过多，已锁定，请 " + seconds + " 秒后重试", data);
    }

    static String failKey(String token, String clientIp) {
        return KEY_PREFIX + "fail:" + token + ":" + clientIp;
    }

    static String lockKey(String token, String clientIp) {
        return KEY_PREFIX + "lock:" + token + ":" + clientIp;
    }
}
