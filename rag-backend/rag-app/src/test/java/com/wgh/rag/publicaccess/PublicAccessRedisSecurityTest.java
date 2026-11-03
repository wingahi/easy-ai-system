package com.wgh.rag.publicaccess;

import com.wgh.rag.chat.mapper.ChatSessionMapper;
import com.wgh.rag.chat.service.ChatWindowService;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.result.ResultCode;
import com.wgh.rag.config.redis.RedisCommandPort;
import com.wgh.rag.kb.mapper.KnowledgeBaseMapper;
import com.wgh.rag.publicaccess.config.PublicAccessProperties;
import com.wgh.rag.publicaccess.entity.ApiKey;
import com.wgh.rag.publicaccess.entity.ShareLink;
import com.wgh.rag.publicaccess.exception.PublicAccessException;
import com.wgh.rag.publicaccess.mapper.ApiKeyMapper;
import com.wgh.rag.publicaccess.mapper.ShareLinkMapper;
import com.wgh.rag.publicaccess.service.ApiKeyService;
import com.wgh.rag.publicaccess.service.ClientIpResolver;
import com.wgh.rag.publicaccess.service.DefaultPublicAccessStore;
import com.wgh.rag.publicaccess.service.DomainWhitelistMatcher;
import com.wgh.rag.publicaccess.service.EmbedAccessService;
import com.wgh.rag.publicaccess.service.IpCidrMatcher;
import com.wgh.rag.publicaccess.service.PublicAccessRateLimiter;
import com.wgh.rag.publicaccess.service.ShareLinkService;
import com.wgh.rag.publicaccess.service.SharePasswordGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Redis 限流降级、Widget 嵌入白名单、分享密码锁定、API Key IP 白名单测试。
 *
 * <p>全部使用内存实现或桩 Redis，不依赖 Docker / 真实 Redis，离线可执行。</p>
 */
class PublicAccessRedisSecurityTest {

    private final PublicAccessProperties properties = new PublicAccessProperties();

    // ===== Redis 限流 / 降级 =====

    @Test
    @DisplayName("REDIS-UNIT-01 Redis Lua 滑动窗口：超限返回 429 并携带重试秒数")
    void redisLuaSlidingWindowOverLimit() {
        ScriptedRedisPort port = new ScriptedRedisPort();
        port.scriptResult = 0L;
        DefaultPublicAccessStore store = new DefaultPublicAccessStore(port);
        PublicAccessRateLimiter limiter = new PublicAccessRateLimiter(store, properties);

        limiter.acquire("api-key", "1", "/api/open/v1/chat", 3);
        assertEquals(1, port.scriptCalls);
        assertTrue(port.lastScript.contains("ZREMRANGEBYSCORE"));
        assertTrue(port.lastScript.contains("ZADD"));

        port.scriptResult = -1L;
        PublicAccessException e = assertThrows(PublicAccessException.class,
                () -> limiter.acquire("api-key", "1", "/api/open/v1/chat", 3));
        assertEquals(ResultCode.QUOTA_EXCEEDED.getCode(), e.getCode());
        assertEquals(60L, ((Number) e.getData().get("retryAfterSeconds")).longValue());
        assertTrue(limiter.distributed());
    }

    @Test
    @DisplayName("REDIS-UNIT-02 Redis 掉线自动降级内存限流，仍能正确拦截超限")
    void redisFailureFallsBackToMemory() {
        ScriptedRedisPort port = new ScriptedRedisPort();
        port.failure = new RuntimeException("connection refused");
        DefaultPublicAccessStore store = new DefaultPublicAccessStore(port);
        PublicAccessRateLimiter limiter = new PublicAccessRateLimiter(store, properties);

        limiter.acquire("share-ip", "127.0.0.1", "/api/public/share/t", 2);
        limiter.acquire("share-ip", "127.0.0.1", "/api/public/share/t", 2);
        assertThrows(PublicAccessException.class,
                () -> limiter.acquire("share-ip", "127.0.0.1", "/api/public/share/t", 2));
        assertFalse(store.distributed(), "Redis 故障后应进入降级窗口");
    }

    @Test
    @DisplayName("REDIS-UNIT-03 Redis 显式关闭时使用内存存储")
    void redisDisabledUsesMemory() {
        DefaultPublicAccessStore store = new DefaultPublicAccessStore(RedisCommandPort.disabled());
        PublicAccessRateLimiter limiter = new PublicAccessRateLimiter(store, properties);
        assertDoesNotThrow(() -> limiter.acquire("share-token", "t", "/api/public/share/t", 1));
        assertThrows(PublicAccessException.class,
                () -> limiter.acquire("share-token", "t", "/api/public/share/t", 1));
        assertFalse(store.distributed());
    }

    // ===== Widget 嵌入白名单 =====

    @Test
    @DisplayName("EMBED-UNIT-01 域名白名单：通配子域命中、裸域与恶意域名拒绝")
    void domainWhitelistMatching() {
        assertTrue(DomainWhitelistMatcher.matchesHost("a.example.com", "*.example.com"));
        assertFalse(DomainWhitelistMatcher.matchesHost("example.com", "*.example.com"));
        assertTrue(DomainWhitelistMatcher.matchesHost("example.com", "example.com,*.foo.com"));
        assertFalse(DomainWhitelistMatcher.matchesHost("evil.com", "*.example.com"));
        assertTrue(DomainWhitelistMatcher.matchesOriginOrReferer(
                null, "https://docs.example.com/page", "*.example.com"));
    }

    @Test
    @DisplayName("EMBED-UNIT-02 授权域名可获取 grant，恶意 Origin 被拒绝")
    void embedAuthorizeHitAndReject() {
        DefaultPublicAccessStore store = new DefaultPublicAccessStore(RedisCommandPort.disabled());
        ClientIpResolver resolver = new ClientIpResolver(properties);
        EmbedAccessService embedAccessService = new EmbedAccessService(store, properties, resolver);
        ShareLink link = embedLink("embed-1", true, "*.example.com", null);

        HttpServletRequest authorized = request("127.0.0.1", Map.of("Origin", "https://a.example.com"));
        String grant = embedAccessService.authorize(link, authorized);
        assertNotNull(grant);
        assertDoesNotThrow(() -> embedAccessService.validate(link,
                request("127.0.0.1", Map.of("X-Embed-Grant", grant))));

        HttpServletRequest rejected = request("127.0.0.1", Map.of("Origin", "https://evil.com"));
        PublicAccessException e = assertThrows(PublicAccessException.class,
                () -> embedAccessService.authorize(link, rejected));
        assertEquals(ResultCode.PERMISSION_DENIED.getCode(), e.getCode());
        assertTrue(e.getMessage().contains("evil.com"));
    }

    @Test
    @DisplayName("EMBED-UNIT-03 IP 白名单 CIDR 命中放行、未命中拒绝；缺失 Origin 默认拒绝")
    void embedIpWhitelist() {
        DefaultPublicAccessStore store = new DefaultPublicAccessStore(RedisCommandPort.disabled());
        EmbedAccessService embedAccessService = new EmbedAccessService(
                store, properties, new ClientIpResolver(properties));
        ShareLink link = embedLink("embed-2", true, null, "10.0.0.0/8");

        assertNotNull(embedAccessService.authorize(link, request("10.1.2.3", Map.of())));
        PublicAccessException outside = assertThrows(PublicAccessException.class,
                () -> embedAccessService.authorize(link, request("192.168.1.1", Map.of())));
        assertTrue(outside.getMessage().contains("Origin/Referer"));
    }

    @Test
    @DisplayName("EMBED-UNIT-04 未开启嵌入的链接拒绝嵌入授权")
    void embedDisabled() {
        DefaultPublicAccessStore store = new DefaultPublicAccessStore(RedisCommandPort.disabled());
        EmbedAccessService embedAccessService = new EmbedAccessService(
                store, properties, new ClientIpResolver(properties));
        ShareLink link = embedLink("embed-3", false, null, null);
        PublicAccessException e = assertThrows(PublicAccessException.class,
                () -> embedAccessService.authorize(link,
                        request("127.0.0.1", Map.of("Origin", "https://a.example.com"))));
        assertTrue(e.getMessage().contains("未开启"));
    }

    @Test
    @DisplayName("EMBED-UNIT-05 CIDR 匹配 IPv4 / IPv6")
    void cidrMatching() {
        assertTrue(IpCidrMatcher.parse("10.0.0.0/8").matches("10.1.2.3"));
        assertFalse(IpCidrMatcher.parse("10.0.0.0/8").matches("192.168.1.1"));
        assertTrue(IpCidrMatcher.parse("203.0.113.0/24").matches("203.0.113.9"));
        assertTrue(IpCidrMatcher.parse("::1").matches("::1"));
        assertEquals("10.0.0.0/8,192.168.1.1/32", IpCidrMatcher.normalizeList("10.0.0.0/8, 192.168.1.1"));
    }

    @Test
    @DisplayName("IP-UNIT-01 仅可信代理的 X-Forwarded-For 生效，客户端伪造头被忽略")
    void trustedProxyOnly() {
        properties.setTrustedProxies(List.of("10.0.0.0/8"));
        ClientIpResolver resolver = new ClientIpResolver(properties);

        assertEquals("203.0.113.5", resolver.resolve(request("10.0.0.5",
                Map.of("X-Forwarded-For", "203.0.113.5, 10.0.0.1"))));
        // 非可信对端直接伪造 XFF，仍取 TCP 对端地址
        assertEquals("192.0.2.10", resolver.resolve(request("192.0.2.10",
                Map.of("X-Forwarded-For", "1.2.3.4"))));
    }

    // ===== 分享密码锁定 =====

    @Test
    @DisplayName("PWD-UNIT-01 连续错误达阈值锁定，锁定期间正确密码也拒绝，过期后恢复")
    void passwordLockAndUnlock() {
        properties.getSharePassword().setMaxFailures(3);
        properties.getSharePassword().setLockMinutes(1);
        AtomicLong now = new AtomicLong(1_000_000L);
        DefaultPublicAccessStore store = new DefaultPublicAccessStore(RedisCommandPort.disabled());
        SharePasswordGuard guard = new SharePasswordGuard(store, properties, now::get);
        ShareLinkService service = new ShareLinkService(
                mock(ShareLinkMapper.class), mock(ChatWindowService.class),
                mock(ChatSessionMapper.class), mock(KnowledgeBaseMapper.class), properties, guard);

        ShareLink link = new ShareLink();
        link.setToken("lock-1");
        link.setPasswordHash(new BCryptPasswordEncoder().encode("secret123"));

        PublicAccessException first = assertThrows(PublicAccessException.class,
                () -> service.requirePassword(link, "bad", "1.1.1.1"));
        assertEquals(2L, ((Number) first.getData().get("remainingAttempts")).longValue());

        PublicAccessException second = assertThrows(PublicAccessException.class,
                () -> service.requirePassword(link, "bad", "1.1.1.1"));
        assertEquals(1L, ((Number) second.getData().get("remainingAttempts")).longValue());

        PublicAccessException locked = assertThrows(PublicAccessException.class,
                () -> service.requirePassword(link, "bad", "1.1.1.1"));
        assertEquals(ResultCode.PUBLIC_ACCESS_LOCKED.getCode(), locked.getCode());
        assertEquals(60L, ((Number) locked.getData().get("lockRemainingSeconds")).longValue());

        PublicAccessException stillLocked = assertThrows(PublicAccessException.class,
                () -> service.requirePassword(link, "secret123", "1.1.1.1"));
        assertEquals(ResultCode.PUBLIC_ACCESS_LOCKED.getCode(), stillLocked.getCode());

        now.addAndGet(61_000L);
        assertDoesNotThrow(() -> service.requirePassword(link, "secret123", "1.1.1.1"));
    }

    // ===== API Key IP 白名单 =====

    @Test
    @DisplayName("APIKEY-UNIT-01 IP 白名单命中放行、未命中 403、空值不限制")
    void apiKeyIpWhitelist() {
        ApiKeyMapper mapper = mock(ApiKeyMapper.class);
        ApiKeyService service = new ApiKeyService(mapper);

        ApiKey key = new ApiKey();
        key.setId(1L);
        key.setIpWhitelist("10.0.0.0/8,192.168.1.1/32");
        assertDoesNotThrow(() -> service.requireIpAllowed(key, "10.9.9.9"));
        assertDoesNotThrow(() -> service.requireIpAllowed(key, "192.168.1.1"));
        BusinessException denied = assertThrows(BusinessException.class,
                () -> service.requireIpAllowed(key, "203.0.113.5"));
        assertEquals(ResultCode.PERMISSION_DENIED.getCode(), denied.getCode());

        key.setIpWhitelist(null);
        assertDoesNotThrow(() -> service.requireIpAllowed(key, "203.0.113.5"));
    }

    @Test
    @DisplayName("APIKEY-UNIT-02 创建时写入规范化 IP 白名单")
    void apiKeyCreateIpWhitelist() {
        ApiKeyMapper mapper = mock(ApiKeyMapper.class);
        when(mapper.insert(any(ApiKey.class))).thenReturn(1);
        ApiKeyService service = new ApiKeyService(mapper);
        ApiKeyService.CreatedKey created = service.create("k", List.of("kb:read"), null,
                "10.0.0.0/8, 192.168.1.1", "tester");
        assertEquals("10.0.0.0/8,192.168.1.1/32", created.record().getIpWhitelist());
        assertEquals(ApiKeyService.sha256(created.plainKey()), created.record().getKeyHash());
    }

    // ===== 辅助 =====

    private ShareLink embedLink(String token, boolean enabled, String domains, String ips) {
        ShareLink link = new ShareLink();
        link.setToken(token);
        link.setScope("WINDOW");
        link.setTargetId(10L);
        link.setEmbedEnabled(enabled);
        link.setEmbedOriginWhitelist(domains);
        link.setEmbedIpWhitelist(ips);
        return link;
    }

    private HttpServletRequest request(String remoteAddr, Map<String, String> headers) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn(remoteAddr);
        when(request.getHeader(any())).thenAnswer(invocation -> headers.get(invocation.getArgument(0)));
        return request;
    }

    /**
     * 可编排返回值 / 抛异常的 Redis 桩，用于验证 Lua 调用与降级路径。
     */
    private static class ScriptedRedisPort implements RedisCommandPort {
        Long scriptResult;
        RuntimeException failure;
        int scriptCalls;
        String lastScript;

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public Long executeScript(String script, List<String> keys, List<String> args) {
            scriptCalls++;
            lastScript = script;
            if (failure != null) {
                throw failure;
            }
            return scriptResult;
        }

        @Override
        public String get(String key) {
            if (failure != null) {
                throw failure;
            }
            return null;
        }

        @Override
        public void set(String key, String value, Duration ttl) {
            if (failure != null) {
                throw failure;
            }
        }

        @Override
        public void delete(String key) {
            if (failure != null) {
                throw failure;
            }
        }
    }
}
