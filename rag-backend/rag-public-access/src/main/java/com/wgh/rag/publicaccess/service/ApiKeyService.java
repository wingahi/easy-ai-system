package com.wgh.rag.publicaccess.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.result.PageResult;
import com.wgh.rag.common.result.ResultCode;
import com.wgh.rag.publicaccess.entity.ApiKey;
import com.wgh.rag.publicaccess.mapper.ApiKeyMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 开放 API Key 管理（创建 / 校验 / 吊销 / 范围管理）
 * <p>
 * 安全设计：明文只在创建时返回一次，库中仅存 SHA-256 哈希；调用时按哈希反查。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApiKeyService {

    private static final String PREFIX = "wgh_sk_";
    private static final SecureRandom RANDOM = new SecureRandom();
    /** lastUsedTime 落库节流窗口：同一 Key 每分钟最多写一次，避免高频调用造成 DB 写放大 */
    private static final long LAST_USED_THROTTLE_MS = 60_000L;

    private final ApiKeyMapper apiKeyMapper;
    /** keyId -> 上次落库时间（毫秒），仅用于节流，进程内有效 */
    private final ConcurrentHashMap<Long, Long> lastUsedWriteAt = new ConcurrentHashMap<>();

    /**
     * 创建 API Key，返回明文（仅此一次）
     */
    @Transactional
    public CreatedKey create(String name, List<String> scopes, LocalDateTime expiresAt, String creator) {
        return create(name, scopes, expiresAt, null, creator);
    }

    /**
     * 创建 API Key，返回明文（仅此一次），可同时设置 IP 白名单。
     */
    @Transactional
    public CreatedKey create(String name, List<String> scopes, LocalDateTime expiresAt,
                             String ipWhitelist, String creator) {
        if (name == null || name.isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "API Key 名称不能为空");
        }
        if (expiresAt != null && !expiresAt.isAfter(LocalDateTime.now())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "API Key 过期时间必须晚于当前时间");
        }
        Set<String> normalized = new LinkedHashSet<>();
        if (scopes != null) {
            for (String s : scopes) {
                if (s != null && !s.isBlank()) normalized.add(s.trim());
            }
        }
        if (normalized.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "至少需要指定一个权限范围");
        }
        String normalizedIpWhitelist = normalizeIpWhitelist(ipWhitelist);

        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(PREFIX);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        String plain = sb.toString();

        ApiKey key = new ApiKey();
        key.setKeyPrefix(plain.substring(0, 12));
        key.setKeyHash(sha256(plain));
        key.setName(name.trim());
        key.setScopes(String.join(",", normalized));
        key.setExpiresAt(expiresAt);
        key.setRevoked(false);
        key.setIpWhitelist(normalizedIpWhitelist);
        key.setCreatedBy(creator);
        apiKeyMapper.insert(key);
        log.info("创建 API Key: id={}, prefix={}, scopes={}, ipWhitelist={}, creator={}",
                key.getId(), key.getKeyPrefix(), key.getScopes(), key.getIpWhitelist(), creator);
        return new CreatedKey(key, plain);
    }

    /**
     * 更新 API Key 的 IP 白名单；传空表示取消限制。
     */
    @Transactional
    public ApiKey updateIpWhitelist(Long id, String ipWhitelist, String operator) {
        ApiKey key = apiKeyMapper.selectById(id);
        if (key == null) {
            throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "API Key 不存在");
        }
        key.setIpWhitelist(normalizeIpWhitelist(ipWhitelist));
        apiKeyMapper.updateById(key);
        log.info("更新 API Key IP 白名单: id={}, ipWhitelist={}, operator={}",
                id, key.getIpWhitelist(), operator);
        key.setKeyHash(null);
        return key;
    }

    /**
     * 校验客户端 IP 是否命中 API Key 的 IP 白名单；未配置表示不限制。
     */
    public void requireIpAllowed(ApiKey key, String clientIp) {
        if (key.getIpWhitelist() == null || key.getIpWhitelist().isBlank()) {
            return;
        }
        List<IpCidrMatcher> matchers;
        try {
            matchers = IpCidrMatcher.parseList(key.getIpWhitelist());
        } catch (IllegalArgumentException e) {
            log.warn("API Key {} 的 IP 白名单配置非法: {}", key.getId(), e.getMessage());
            throw new BusinessException(ResultCode.PERMISSION_DENIED, "API Key IP 白名单配置非法，请联系管理员");
        }
        for (IpCidrMatcher matcher : matchers) {
            if (matcher.matches(clientIp)) {
                return;
            }
        }
        throw new BusinessException(ResultCode.PERMISSION_DENIED,
                "客户端 IP " + clientIp + " 不在该 API Key 的 IP 白名单内");
    }

    private String normalizeIpWhitelist(String raw) {
        try {
            return IpCidrMatcher.normalizeList(raw);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "IP 白名单格式错误：" + e.getMessage());
        }
    }

    /**
     * 校验明文 Key：不存在 / 已吊销 / 已过期均抛明确错误
     */
    public ApiKey verify(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            throw new BusinessException(ResultCode.TOKEN_INVALID, "缺少 API Key");
        }
        ApiKey key = apiKeyMapper.selectOne(new LambdaQueryWrapper<ApiKey>()
                .eq(ApiKey::getKeyHash, sha256(rawKey.trim()))
                .last("LIMIT 1"));
        if (key == null) {
            throw new BusinessException(ResultCode.TOKEN_INVALID, "API Key 无效");
        }
        if (Boolean.TRUE.equals(key.getRevoked())) {
            throw new BusinessException(ResultCode.TOKEN_INVALID, "API Key 已被吊销");
        }
        if (key.getExpiresAt() != null && !key.getExpiresAt().isAfter(LocalDateTime.now())) {
            throw new BusinessException(ResultCode.TOKEN_EXPIRED, "API Key 已过期");
        }
        touchLastUsed(key);
        return key;
    }

    /**
     * 更新 lastUsedTime，按 Key 做时间窗口节流（多实例部署时各实例独立节流）。
     */
    private void touchLastUsed(ApiKey key) {
        Long keyId = key.getId();
        if (keyId == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Long lastWriteAt = lastUsedWriteAt.get(keyId);
        if (lastWriteAt != null && now - lastWriteAt < LAST_USED_THROTTLE_MS) {
            return;
        }
        lastUsedWriteAt.put(keyId, now);
        key.setLastUsedTime(LocalDateTime.now());
        apiKeyMapper.updateById(key);
    }

    /**
     * 校验权限范围；未声明所需 scope 时抛 403
     */
    public void requireScope(ApiKey key, String scope) {
        if (scope == null || scope.isBlank()) return;
        Set<String> granted = new LinkedHashSet<>();
        if (key.getScopes() != null) {
            Arrays.stream(key.getScopes().split(",")).map(String::trim).forEach(granted::add);
        }
        if (granted.contains("*") || granted.contains(scope)) return;
        throw new BusinessException(ResultCode.PERMISSION_DENIED,
                "API Key 缺少权限范围：" + scope);
    }

    @Transactional
    public void revoke(Long id, String operator) {
        ApiKey key = apiKeyMapper.selectById(id);
        if (key == null) {
            throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "API Key 不存在");
        }
        key.setRevoked(true);
        apiKeyMapper.updateById(key);
        log.info("吊销 API Key: id={}, operator={}", id, operator);
    }

    public PageResult<ApiKey> page(Long page, Long size, String keyword) {
        LambdaQueryWrapper<ApiKey> wrapper = new LambdaQueryWrapper<ApiKey>()
                .and(keyword != null && !keyword.isBlank(), w -> w
                        .like(ApiKey::getName, keyword)
                        .or().like(ApiKey::getKeyPrefix, keyword))
                .orderByDesc(ApiKey::getId);
        Page<ApiKey> result = apiKeyMapper.selectPage(Page.of(page, size), wrapper);
        // 不返回哈希字段
        result.getRecords().forEach(k -> k.setKeyHash(null));
        return new PageResult<>(result.getTotal(), result.getCurrent(), result.getSize(), result.getRecords());
    }

    public ApiKey findById(Long id) {
        return apiKeyMapper.selectById(id);
    }

    public static String sha256(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 创建结果（含明文，仅返回一次）
     */
    public record CreatedKey(ApiKey record, String plainKey) {
    }
}
