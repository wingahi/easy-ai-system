package com.wgh.rag.auth.cache;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 用户信息缓存配置。
 *
 * <p>只缓存 {@code wgh_user} 的身份/状态字段，额度与用量（{@code wgh_user_quota_config}、
 * {@code wgh_quota_usage}）由配额模块直读数据库，不进入本缓存。</p>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "wgh.auth.user-cache")
public class UserCacheProperties {

    /** 是否启用用户信息缓存；关闭后所有读取直接回源数据库 */
    private boolean enabled = true;

    /** 缓存有效期，默认 30 分钟；不允许配置为永久（<=0 时回退默认值） */
    private Duration ttl = Duration.ofMinutes(30);

    /**
     * 回填锁最长等待时间，默认 200ms。
     * <p>超时后不再等待，直接回源数据库且不写缓存，避免缓存击穿时的请求堆积。</p>
     */
    private Duration lockWaitTimeout = Duration.ofMillis(200);
}
