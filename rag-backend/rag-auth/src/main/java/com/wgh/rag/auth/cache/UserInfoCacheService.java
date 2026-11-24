package com.wgh.rag.auth.cache;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.wgh.rag.auth.entity.WghUser;
import com.wgh.rag.auth.mapper.UserMapper;
import com.wgh.rag.common.metrics.RagMetrics;
import com.wgh.rag.config.redis.RedisCommandPort;
import com.wgh.rag.config.redis.RedisDistributedLock;
import com.wgh.rag.config.redis.RedisKeyNamespace;
import com.wgh.rag.config.redis.RedisUnavailableException;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 用户信息缓存（按用户 ID）。
 *
 * <p>缓存内容只包含 {@code wgh_user} 的身份与账号状态字段，明确排除
 * {@code password_hash}、OIDC 外部标识等敏感/非必要信息；额度与用量分别位于
 * {@code wgh_user_quota_config} / {@code wgh_quota_usage}，由配额模块直读数据库，
 * 不进入本缓存，避免高频计数被缓存固化或读缓存绕过额度校验。</p>
 *
 * <p>读写策略：</p>
 * <ul>
 *   <li>读：命中直接返回；未命中时用 Redis 分布式锁串行化回填，拿到锁后双重检查，
 *       仍为空才查库并写缓存。等待锁有上限，抢不到锁的请求直接回源且不写缓存，避免惊群。</li>
 *   <li>写：业务写路径在<b>数据库事务提交后</b>删除缓存（{@link #evictAfterCommit(Long)}）。
 *       选择删除而非更新，是为了避免提交后再查库的竞态与部分更新写入脏值；删除由下次读回源重建。</li>
 *   <li>降级：Redis 不可用时直接查库并记录 WARN + 指标，绝不因缓存故障阻断用户读取，
 *       也不降级为进程内锁（多实例下无法互斥）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserInfoCacheService {

    /** Redis key 模块段：{@code {root}:auth:user:{userId}} */
    static final String KEY_MODULE = "auth:user";

    /** 回填锁逻辑名，实际 key 由 {@link RedisDistributedLock} 加 {@code {root}:lock:} 前缀 */
    private static final String LOCK_PREFIX = "auth-user-refill:";

    /** 回填锁租期：回填只包含一次主键查询，5s 足够；进程异常退出后自动释放 */
    private static final Duration LOCK_TTL = Duration.ofSeconds(5);

    /** TTL 兜底值：配置缺失或非法时仍保持 30 分钟，绝不使用永久键 */
    private static final Duration FALLBACK_TTL = Duration.ofMinutes(30);

    /** 锁等待默认上限 */
    private static final Duration FALLBACK_LOCK_WAIT = Duration.ofMillis(200);

    /** 获取锁耗时超过该阈值时记一次「锁等待」，用于观察并发回填压力 */
    private static final long LOCK_WAIT_METRIC_THRESHOLD_MILLIS = 50L;

    /** 降级 WARN 的最小间隔，避免 Redis 故障期间每个请求刷一条日志 */
    private static final long DEGRADED_WARN_INTERVAL_MILLIS = 60_000L;

    private final UserMapper userMapper;
    private final RedisCommandPort redis;
    private final RedisDistributedLock redisLock;
    private final UserCacheProperties properties;
    private final RagMetrics metrics;

    /** 只用于本缓存的序列化器：支持 java.time，忽略未知字段以兼容后续字段增减 */
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final AtomicLong lastDegradedWarnAt = new AtomicLong(0L);

    /**
     * 按用户 ID 读取用户信息：优先缓存，未命中时加分布式锁回填。
     *
     * @param userId 用户 ID
     * @return 用户信息；不存在或已逻辑删除返回 {@code null}
     */
    public WghUser load(Long userId) {
        if (userId == null) {
            return null;
        }
        if (!properties.isEnabled()) {
            return userMapper.selectById(userId);
        }

        String key = cacheKey(userId);
        CachedUserInfo cached;
        try {
            cached = readCache(key, userId);
        } catch (RedisUnavailableException e) {
            return degradeToDatabase(userId, e);
        }
        if (cached != null) {
            metrics.recordUserCache("hit");
            return cached.toUser();
        }
        metrics.recordUserCache("miss");

        RedisDistributedLock.LockHandle handle;
        long lockStartNanos = System.nanoTime();
        try {
            handle = redisLock.tryAcquire(LOCK_PREFIX + userId, LOCK_TTL, lockWaitTimeout());
        } catch (RedisUnavailableException e) {
            return degradeToDatabase(userId, e);
        }
        if (handle == null) {
            // 等待上限内没抢到锁：直接回源，不重复写缓存，避免惊群
            metrics.recordUserCache("lock_lost");
            return userMapper.selectById(userId);
        }
        long waitedMillis = (System.nanoTime() - lockStartNanos) / 1_000_000L;
        if (waitedMillis >= LOCK_WAIT_METRIC_THRESHOLD_MILLIS) {
            metrics.recordUserCache("lock_wait");
        }

        try {
            // 双重检查：可能已在等待锁期间由其他实例回填
            CachedUserInfo rechecked = readCache(key, userId);
            if (rechecked != null) {
                metrics.recordUserCache("hit");
                return rechecked.toUser();
            }
            WghUser user = userMapper.selectById(userId);
            metrics.recordUserCache("db_load");
            if (user != null) {
                writeCache(key, user, userId);
            }
            return user;
        } catch (RedisUnavailableException e) {
            return degradeToDatabase(userId, e);
        } finally {
            releaseLock(handle, userId);
        }
    }

    /**
     * 数据库事务提交后删除缓存；无事务时立即删除（此时 DB 更新已自动提交）。
     * <p>必须放在提交之后，避免事务回滚留下“新值在缓存、旧值在库”的脏状态。</p>
     */
    public void evictAfterCommit(Long userId) {
        if (userId == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evict(userId);
                }
            });
            return;
        }
        evict(userId);
    }

    /** 立即删除缓存；失败只记录 WARN + 指标，由 30 分钟 TTL 兜底 */
    public void evict(Long userId) {
        if (userId == null || !properties.isEnabled() || !redis.enabled()) {
            return;
        }
        try {
            redis.delete(cacheKey(userId));
            metrics.recordUserCache("evict");
        } catch (RuntimeException e) {
            metrics.recordUserCache("evict_failed");
            log.warn("用户缓存失效失败，等待 TTL 自然过期 userId={}: {}", userId, e.getMessage());
        }
    }

    // ==================== 内部实现 ====================

    private CachedUserInfo readCache(String key, Long userId) {
        String json = redis.get(key);
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            CachedUserInfo info = objectMapper.readValue(json, CachedUserInfo.class);
            if (info.getId() == null || !info.getId().equals(userId) || !StringUtils.hasText(info.getUsername())) {
                // 旧格式/被污染内容：按未命中处理并清除，绝不让反序列化异常冒到调用方
                log.warn("用户缓存内容不完整，按未命中处理 userId={}", userId);
                deleteQuietly(key, userId);
                return null;
            }
            return info;
        } catch (Exception e) {
            log.warn("用户缓存反序列化失败，按未命中处理 userId={}", userId);
            deleteQuietly(key, userId);
            return null;
        }
    }

    private void writeCache(String key, WghUser user, Long userId) {
        try {
            String json = objectMapper.writeValueAsString(CachedUserInfo.from(user));
            redis.set(key, json, ttl());
            metrics.recordUserCache("refill");
        } catch (Exception e) {
            metrics.recordUserCache("refill_failed");
            log.warn("用户缓存回填失败，本次直接返回数据库结果 userId={}: {}", userId, e.getMessage());
        }
    }

    private WghUser degradeToDatabase(Long userId, RuntimeException cause) {
        metrics.recordUserCache("degraded");
        long now = System.currentTimeMillis();
        long last = lastDegradedWarnAt.get();
        if (now - last >= DEGRADED_WARN_INTERVAL_MILLIS && lastDegradedWarnAt.compareAndSet(last, now)) {
            log.warn("用户缓存不可用，降级为直读数据库 userId={}: {}", userId, cause.getMessage());
        }
        return userMapper.selectById(userId);
    }

    private void releaseLock(RedisDistributedLock.LockHandle handle, Long userId) {
        try {
            redisLock.unlock(handle);
        } catch (RuntimeException e) {
            log.warn("释放用户缓存回填锁失败（TTL 到期自动释放）userId={}: {}", userId, e.getMessage());
        }
    }

    private void deleteQuietly(String key, Long userId) {
        try {
            redis.delete(key);
        } catch (RuntimeException e) {
            log.warn("清理异常用户缓存失败 userId={}: {}", userId, e.getMessage());
        }
    }

    private static String cacheKey(Long userId) {
        return RedisKeyNamespace.root() + ":" + KEY_MODULE + ":" + userId;
    }

    private Duration ttl() {
        Duration configured = properties.getTtl();
        if (configured == null || configured.isZero() || configured.isNegative()) {
            return FALLBACK_TTL;
        }
        return configured;
    }

    private Duration lockWaitTimeout() {
        Duration configured = properties.getLockWaitTimeout();
        return configured == null ? FALLBACK_LOCK_WAIT : configured;
    }

    /**
     * 缓存值：仅安全字段，JSON 结构变更通过忽略未知字段 + 缺失字段置空保持兼容。
     * <p>不包含 {@code passwordHash}、{@code oidcSubject}/{@code oidcIssuer}，
     * 也不包含任何额度/用量计数。</p>
     */
    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CachedUserInfo {

        private Long id;
        private String username;
        private String name;
        private String email;
        private String tenantId;
        private Integer status;
        private String source;
        private Integer trial;
        private LocalDateTime expireAt;
        private String remark;
        private Integer passwordMustChange;
        private Integer passwordVersion;

        static CachedUserInfo from(WghUser user) {
            CachedUserInfo info = new CachedUserInfo();
            info.setId(user.getId());
            info.setUsername(user.getUsername());
            info.setName(user.getName());
            info.setEmail(user.getEmail());
            info.setTenantId(user.getTenantId());
            info.setStatus(user.getStatus());
            info.setSource(user.getSource());
            info.setTrial(user.getTrial());
            info.setExpireAt(user.getExpireAt());
            info.setRemark(user.getRemark());
            info.setPasswordMustChange(user.getPasswordMustChange());
            info.setPasswordVersion(user.getPasswordVersion());
            return info;
        }

        WghUser toUser() {
            WghUser user = new WghUser();
            user.setId(id);
            user.setUsername(username);
            user.setName(name);
            user.setEmail(email);
            user.setTenantId(tenantId);
            user.setStatus(status);
            user.setSource(source);
            user.setTrial(trial);
            user.setExpireAt(expireAt);
            user.setRemark(remark);
            user.setPasswordMustChange(passwordMustChange);
            user.setPasswordVersion(passwordVersion);
            return user;
        }
    }
}
