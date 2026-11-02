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

    private final ApiKeyMapper apiKeyMapper;

    /**
     * 创建 API Key，返回明文（仅此一次）
     */
    @Transactional
    public CreatedKey create(String name, List<String> scopes, LocalDateTime expiresAt, String creator) {
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
        key.setCreatedBy(creator);
        apiKeyMapper.insert(key);
        log.info("创建 API Key: id={}, prefix={}, scopes={}, creator={}",
                key.getId(), key.getKeyPrefix(), key.getScopes(), creator);
        return new CreatedKey(key, plain);
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
        key.setLastUsedTime(LocalDateTime.now());
        apiKeyMapper.updateById(key);
        return key;
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
