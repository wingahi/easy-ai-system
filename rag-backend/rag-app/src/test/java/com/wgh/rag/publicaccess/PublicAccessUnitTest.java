package com.wgh.rag.publicaccess;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.wgh.rag.chat.mapper.ChatSessionMapper;
import com.wgh.rag.chat.service.ChatWindowService;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.result.ResultCode;
import com.wgh.rag.kb.mapper.KnowledgeBaseMapper;
import com.wgh.rag.publicaccess.config.PublicAccessProperties;
import com.wgh.rag.publicaccess.entity.ApiKey;
import com.wgh.rag.publicaccess.entity.ShareLink;
import com.wgh.rag.publicaccess.mapper.ApiKeyMapper;
import com.wgh.rag.publicaccess.mapper.ShareLinkMapper;
import com.wgh.rag.publicaccess.service.ApiKeyService;
import com.wgh.rag.publicaccess.service.DefaultPublicAccessStore;
import com.wgh.rag.publicaccess.service.PublicAccessRateLimiter;
import com.wgh.rag.publicaccess.service.ShareLinkService;
import com.wgh.rag.publicaccess.service.SharePasswordGuard;
import com.wgh.rag.config.redis.RedisCommandPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 分享链接 / API Key / 限流 单元测试
 * <p>
 * 说明：Mapper 用 Mockito 打桩，不依赖真实数据库；覆盖有效期、吊销、次数、
 * 密码、范围、哈希与限流边界。HTTP 层行为由浏览器用例（docs/test-cases.md SHARE/OPEN 组）覆盖。
 */
class PublicAccessUnitTest {

    private final ShareLinkMapper shareLinkMapper = mock(ShareLinkMapper.class);
    private final ChatWindowService windowService = mock(ChatWindowService.class);
    private final ChatSessionMapper sessionMapper = mock(ChatSessionMapper.class);
    private final KnowledgeBaseMapper knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
    private final PublicAccessProperties properties = new PublicAccessProperties();
    private final DefaultPublicAccessStore store = new DefaultPublicAccessStore(RedisCommandPort.disabled());
    private final SharePasswordGuard passwordGuard = new SharePasswordGuard(store, properties);
    private final ShareLinkService shareLinkService = new ShareLinkService(
            shareLinkMapper, windowService, sessionMapper, knowledgeBaseMapper, properties, passwordGuard);

    private final ApiKeyMapper apiKeyMapper = mock(ApiKeyMapper.class);
    private final ApiKeyService apiKeyService = new ApiKeyService(apiKeyMapper);
    private final PublicAccessRateLimiter rateLimiter = new PublicAccessRateLimiter(store, properties);

    private ShareLink link(String token, boolean revoked, LocalDateTime expiresAt, boolean withPassword) {
        ShareLink link = new ShareLink();
        link.setId(1L);
        link.setToken(token);
        link.setScope("WINDOW");
        link.setTargetId(10L);
        link.setRevoked(revoked);
        link.setExpiresAt(expiresAt);
        link.setMaxUses(0);
        link.setUseCount(0);
        if (withPassword) link.setPasswordHash(new BCryptPasswordEncoder().encode("secret123"));
        return link;
    }

    // ===== 分享链接校验 =====

    @Test
    @DisplayName("SHARE-UNIT-01 无效 token 返回明确错误")
    void invalidToken() {
        when(shareLinkMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        BusinessException e = assertThrows(BusinessException.class,
                () -> shareLinkService.resolveActive("not-exist"));
        assertEquals("分享链接无效", e.getMessage());
    }

    @Test
    @DisplayName("SHARE-UNIT-02 已吊销链接返回明确错误")
    void revokedLink() {
        when(shareLinkMapper.selectOne(any(Wrapper.class))).thenReturn(link("t1", true, null, false));
        BusinessException e = assertThrows(BusinessException.class,
                () -> shareLinkService.resolveActive("t1"));
        assertEquals("分享链接已被吊销", e.getMessage());
    }

    @Test
    @DisplayName("SHARE-UNIT-03 过期链接返回明确错误")
    void expiredLink() {
        when(shareLinkMapper.selectOne(any(Wrapper.class)))
                .thenReturn(link("t2", false, LocalDateTime.now().minusMinutes(1), false));
        BusinessException e = assertThrows(BusinessException.class,
                () -> shareLinkService.resolveActive("t2"));
        assertEquals("分享链接已过期", e.getMessage());
    }

    @Test
    @DisplayName("SHARE-UNIT-04 使用次数用尽返回 429")
    void usesExhausted() {
        ShareLink l = link("t3", false, null, false);
        l.setMaxUses(5);
        l.setUseCount(5);
        when(shareLinkMapper.selectOne(any(Wrapper.class))).thenReturn(l);
        BusinessException e = assertThrows(BusinessException.class,
                () -> shareLinkService.resolveActive("t3"));
        assertEquals(ResultCode.QUOTA_EXCEEDED.getCode(), e.getCode());
        assertEquals("分享链接访问次数已用尽", e.getMessage());
    }

    @Test
    @DisplayName("SHARE-UNIT-05 需要密码但未提供")
    void passwordRequired() {
        ShareLink l = link("t4", false, null, true);
        BusinessException e = assertThrows(BusinessException.class,
                () -> shareLinkService.requirePassword(l, null));
        assertEquals("该分享链接需要访问密码", e.getMessage());
    }

    @Test
    @DisplayName("SHARE-UNIT-06 密码错误")
    void passwordWrong() {
        ShareLink l = link("t5", false, null, true);
        BusinessException e = assertThrows(BusinessException.class,
                () -> shareLinkService.requirePassword(l, "wrong"));
        assertEquals("访问密码错误", e.getMessage());
    }

    @Test
    @DisplayName("SHARE-UNIT-07 密码正确通过")
    void passwordCorrect() {
        ShareLink l = link("t6", false, null, true);
        assertDoesNotThrow(() -> shareLinkService.requirePassword(l, "secret123"));
        assertTrue(shareLinkService.matchesPassword(l, "secret123"));
        assertFalse(shareLinkService.matchesPassword(l, "bad"));
    }

    @Test
    @DisplayName("SHARE-UNIT-08 范围不匹配返回越权错误")
    void scopeMismatch() {
        ShareLink l = link("t7", false, null, false);
        l.setScope(ShareLinkService.SCOPE_KB);
        BusinessException e = assertThrows(BusinessException.class,
                () -> shareLinkService.requireScope(l, ShareLinkService.SCOPE_WINDOW));
        assertEquals(ResultCode.PERMISSION_DENIED.getCode(), e.getCode());
        assertTrue(e.getMessage().contains("不支持该操作"));
    }

    @Test
    @DisplayName("SHARE-UNIT-09 创建链接：有效期超过上限被拒绝")
    void createExpiryTooLong() {
        ShareLink req = new ShareLink();
        req.setScope("WINDOW");
        req.setTargetId(10L);
        req.setExpiresAt(LocalDateTime.now().plusDays(properties.getShareMaxExpireDays() + 1));
        BusinessException e = assertThrows(BusinessException.class,
                () -> shareLinkService.create(req, "tester"));
        assertTrue(e.getMessage().contains("有效期超过上限"));
    }

    @Test
    @DisplayName("SHARE-UNIT-10 创建 WINDOW 链接：token 前缀、默认有效期、计数初始化")
    void createWindowLink() {
        ShareLink req = new ShareLink();
        req.setScope("window");
        req.setTargetId(10L);
        when(shareLinkMapper.insert(any(ShareLink.class))).thenReturn(1);
        ShareLink created = shareLinkService.create(req, "tester");
        assertTrue(created.getToken().startsWith("s_"));
        assertEquals("WINDOW", created.getScope());
        assertEquals(0, created.getUseCount());
        assertFalse(created.getRevoked());
        assertTrue(created.getExpiresAt().isAfter(
                LocalDateTime.now().plusDays(properties.getShareDefaultExpireDays() - 1)));
    }

    @Test
    @DisplayName("SHARE-UNIT-11 创建 KB 链接：知识库不存在时报错")
    void createKbNotFound() {
        ShareLink req = new ShareLink();
        req.setScope("KB");
        req.setTargetId(99L);
        when(knowledgeBaseMapper.selectById(99L)).thenReturn(null);
        BusinessException e = assertThrows(BusinessException.class,
                () -> shareLinkService.create(req, "tester"));
        assertEquals("知识库不存在", e.getMessage());
    }

    @Test
    @DisplayName("SHARE-UNIT-12 使用次数并发扣减失败时返回 429")
    void consumeUseConflict() {
        ShareLink l = link("t8", false, null, false);
        when(shareLinkMapper.update(any(), any(Wrapper.class))).thenReturn(0);
        BusinessException e = assertThrows(BusinessException.class,
                () -> shareLinkService.consumeUse(l));
        assertEquals(ResultCode.QUOTA_EXCEEDED.getCode(), e.getCode());
    }

    // ===== API Key =====

    @Test
    @DisplayName("OPEN-UNIT-01 创建 Key：明文前缀 + 仅存 SHA-256")
    void createApiKey() {
        when(apiKeyMapper.insert(any(ApiKey.class))).thenReturn(1);
        ApiKeyService.CreatedKey created = apiKeyService.create(
                "测试 Key", List.of("chat:write", "kb:read"), null, "tester");
        assertTrue(created.plainKey().startsWith("wgh_sk_"));
        assertEquals(created.plainKey().substring(0, 12), created.record().getKeyPrefix());
        assertEquals(ApiKeyService.sha256(created.plainKey()), created.record().getKeyHash());
        assertEquals("chat:write,kb:read", created.record().getScopes());
    }

    @Test
    @DisplayName("OPEN-UNIT-02 创建 Key：缺少权限范围报错")
    void createApiKeyWithoutScope() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> apiKeyService.create("k", List.of(), null, "tester"));
        assertEquals("至少需要指定一个权限范围", e.getMessage());
    }

    @Test
    @DisplayName("OPEN-UNIT-03 校验 Key：无效 / 吊销 / 过期")
    void verifyApiKeyErrors() {
        when(apiKeyMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        assertEquals("API Key 无效",
                assertThrows(BusinessException.class, () -> apiKeyService.verify("wgh_sk_x")).getMessage());

        ApiKey revoked = new ApiKey();
        revoked.setId(2L);
        revoked.setRevoked(true);
        when(apiKeyMapper.selectOne(any(Wrapper.class))).thenReturn(revoked);
        assertEquals("API Key 已被吊销",
                assertThrows(BusinessException.class, () -> apiKeyService.verify("wgh_sk_y")).getMessage());

        ApiKey expired = new ApiKey();
        expired.setId(3L);
        expired.setRevoked(false);
        expired.setExpiresAt(LocalDateTime.now().minusDays(1));
        when(apiKeyMapper.selectOne(any(Wrapper.class))).thenReturn(expired);
        BusinessException e = assertThrows(BusinessException.class,
                () -> apiKeyService.verify("wgh_sk_z"));
        assertEquals(ResultCode.TOKEN_EXPIRED.getCode(), e.getCode());
    }

    @Test
    @DisplayName("OPEN-UNIT-04 权限范围校验：* 通配与缺失")
    void requireScope() {
        ApiKey wildcard = new ApiKey();
        wildcard.setScopes("*");
        assertDoesNotThrow(() -> apiKeyService.requireScope(wildcard, "chat:write"));

        ApiKey limited = new ApiKey();
        limited.setScopes("kb:read");
        BusinessException e = assertThrows(BusinessException.class,
                () -> apiKeyService.requireScope(limited, "chat:write"));
        assertEquals(ResultCode.PERMISSION_DENIED.getCode(), e.getCode());
        assertTrue(e.getMessage().contains("chat:write"));
    }

    @Test
    @DisplayName("OPEN-UNIT-05 SHA-256 稳定输出")
    void sha256Stable() {
        assertEquals(ApiKeyService.sha256("abc"), ApiKeyService.sha256("abc"));
        assertEquals(64, ApiKeyService.sha256("abc").length());
    }

    // ===== 限流 =====

    @Test
    @DisplayName("SHARE-UNIT-13 限流：窗口内超限返回 429，窗口外恢复")
    void rateLimiter() {
        for (int i = 0; i < 3; i++) {
            rateLimiter.acquire("test", "k1", "/api/public/share/t1", 3);
        }
        BusinessException e = assertThrows(BusinessException.class,
                () -> rateLimiter.acquire("test", "k1", "/api/public/share/t1", 3));
        assertEquals(ResultCode.QUOTA_EXCEEDED.getCode(), e.getCode());
        // 不同 key 独立计数
        assertDoesNotThrow(() -> rateLimiter.acquire("test", "k2", "/api/public/share/t1", 3));
    }
}
