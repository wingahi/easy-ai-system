package com.wgh.rag.publicaccess.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wgh.rag.chat.entity.ChatSession;
import com.wgh.rag.chat.mapper.ChatSessionMapper;
import com.wgh.rag.chat.service.ChatWindowService;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.result.PageResult;
import com.wgh.rag.common.result.ResultCode;
import com.wgh.rag.kb.mapper.KnowledgeBaseMapper;
import com.wgh.rag.publicaccess.config.PublicAccessProperties;
import com.wgh.rag.publicaccess.entity.ShareLink;
import com.wgh.rag.publicaccess.mapper.ShareLinkMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 分享链接管理 + 访问校验（有效期 / 密码 / 吊销 / 范围 / 次数）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShareLinkService {

    public static final String SCOPE_WINDOW = "WINDOW";
    public static final String SCOPE_SESSION = "SESSION";
    public static final String SCOPE_KB = "KB";
    private static final Set<String> SCOPES = Set.of(SCOPE_WINDOW, SCOPE_SESSION, SCOPE_KB);

    private final ShareLinkMapper shareLinkMapper;
    private final ChatWindowService windowService;
    private final ChatSessionMapper sessionMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final PublicAccessProperties properties;
    private final SharePasswordGuard passwordGuard;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /**
     * 创建分享链接
     */
    @Transactional
    public ShareLink create(ShareLink req, String creator) {
        String scope = req.getScope() == null ? "" : req.getScope().toUpperCase();
        if (!SCOPES.contains(scope)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "scope 仅支持 WINDOW / SESSION / KB");
        }
        if (req.getTargetId() == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "targetId 不能为空");
        }

        Long windowId;
        if (SCOPE_KB.equals(scope)) {
            if (knowledgeBaseMapper.selectById(req.getTargetId()) == null) {
                throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "知识库不存在");
            }
            windowId = null;
        } else if (SCOPE_SESSION.equals(scope)) {
            ChatSession session = sessionMapper.selectById(req.getTargetId());
            if (session == null || !"ACTIVE".equals(session.getStatus())) {
                throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "会话不存在或已归档");
            }
            windowId = session.getChatWindowId();
            windowService.getById(windowId);
        } else {
            windowService.getById(req.getTargetId());
            windowId = req.getTargetId();
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = req.getExpiresAt();
        LocalDateTime maxExpire = now.plusDays(Math.max(1, properties.getShareMaxExpireDays()));
        if (expiresAt == null) {
            expiresAt = now.plusDays(Math.max(1, properties.getShareDefaultExpireDays()));
        }
        if (!expiresAt.isAfter(now)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "有效期必须晚于当前时间");
        }
        if (expiresAt.isAfter(maxExpire)) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "有效期超过上限（最多 " + properties.getShareMaxExpireDays() + " 天）");
        }
        if (req.getMaxUses() != null && req.getMaxUses() < 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "最大使用次数不能为负数");
        }

        ShareLink link = new ShareLink();
        link.setToken("s_" + UUID.randomUUID().toString().replace("-", ""));
        link.setName(req.getName() == null || req.getName().isBlank()
                ? (windowId == null ? "知识库分享" : "聊天分享") : req.getName().trim());
        link.setScope(scope);
        link.setTargetId(req.getTargetId());
        link.setExpiresAt(expiresAt);
        link.setPasswordHash(req.getPasswordHash() == null || req.getPasswordHash().isBlank()
                ? null : passwordEncoder.encode(req.getPasswordHash()));
        link.setMaxUses(req.getMaxUses() == null ? 0 : req.getMaxUses());
        link.setUseCount(0);
        link.setRevoked(false);
        link.setEmbedEnabled(Boolean.TRUE.equals(req.getEmbedEnabled()));
        link.setEmbedOriginWhitelist(normalizeDomainWhitelist(req.getEmbedOriginWhitelist()));
        link.setEmbedIpWhitelist(normalizeIpWhitelist(req.getEmbedIpWhitelist()));
        link.setCreatedBy(creator);
        shareLinkMapper.insert(link);
        log.info("创建分享链接: id={}, scope={}, target={}, creator={}", link.getId(), scope, req.getTargetId(), creator);
        return link;
    }

    /**
     * 解析有效链接：不存在 / 已吊销 / 已过期 / 次数用尽均给出明确错误
     */
    public ShareLink resolveActive(String token) {
        if (token == null || token.isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "分享 token 不能为空");
        }
        ShareLink link = shareLinkMapper.selectOne(new LambdaQueryWrapper<ShareLink>()
                .eq(ShareLink::getToken, token)
                .last("LIMIT 1"));
        if (link == null) {
            throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "分享链接无效");
        }
        if (Boolean.TRUE.equals(link.getRevoked())) {
            throw new BusinessException(ResultCode.OPERATION_NOT_ALLOWED, "分享链接已被吊销");
        }
        if (link.getExpiresAt() != null && !link.getExpiresAt().isAfter(LocalDateTime.now())) {
            throw new BusinessException(ResultCode.OPERATION_NOT_ALLOWED, "分享链接已过期");
        }
        if (link.getMaxUses() != null && link.getMaxUses() > 0
                && link.getUseCount() != null && link.getUseCount() >= link.getMaxUses()) {
            throw new BusinessException(ResultCode.QUOTA_EXCEEDED, "分享链接访问次数已用尽");
        }
        return link;
    }

    /**
     * 校验访问密码（不启用失败锁定，兼容旧调用）
     */
    public void requirePassword(ShareLink link, String password) {
        requirePassword(link, password, null);
    }

    /**
     * 校验访问密码，并按「token + 客户端 IP」累计失败次数、触发锁定。
     */
    public void requirePassword(ShareLink link, String password, String clientIp) {
        if (link.getPasswordHash() == null || link.getPasswordHash().isBlank()) return;
        if (clientIp != null && !clientIp.isBlank()) {
            long lockSeconds = passwordGuard.lockRemainingSeconds(link.getToken(), clientIp);
            if (lockSeconds > 0) {
                throw passwordGuard.lockedException(lockSeconds);
            }
        }
        if (password == null || password.isBlank()) {
            // 不使用 1001/1002，避免前端拦截器把匿名访客误判为登录失效
            throw new BusinessException(ResultCode.OPERATION_NOT_ALLOWED, "该分享链接需要访问密码");
        }
        if (!passwordEncoder.matches(password, link.getPasswordHash())) {
            if (clientIp == null || clientIp.isBlank()) {
                throw new BusinessException(ResultCode.PERMISSION_DENIED, "访问密码错误");
            }
            // 达到阈值时抛出锁定异常，否则抛出带剩余次数的异常
            passwordGuard.onFailure(link, clientIp);
        }
        if (clientIp != null && !clientIp.isBlank()) {
            passwordGuard.onSuccess(link, clientIp);
        }
    }

    /**
     * 更新分享链接的嵌入白名单配置。
     */
    @Transactional
    public ShareLink updateEmbedConfig(Long id, Boolean embedEnabled, String embedOriginWhitelist,
                                       String embedIpWhitelist) {
        ShareLink link = shareLinkMapper.selectById(id);
        if (link == null) {
            throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "分享链接不存在");
        }
        link.setEmbedEnabled(Boolean.TRUE.equals(embedEnabled));
        link.setEmbedOriginWhitelist(normalizeDomainWhitelist(embedOriginWhitelist));
        link.setEmbedIpWhitelist(normalizeIpWhitelist(embedIpWhitelist));
        shareLinkMapper.updateById(link);
        return link;
    }

    private String normalizeDomainWhitelist(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        List<String> hosts = new ArrayList<>();
        for (String part : raw.split("[,;\\s]+")) {
            if (part.isBlank()) {
                continue;
            }
            String host = DomainWhitelistMatcher.extractHost(part);
            if (host == null) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "域名白名单格式错误：" + part);
            }
            hosts.add(host);
        }
        return hosts.isEmpty() ? null : String.join(",", hosts);
    }

    private String normalizeIpWhitelist(String raw) {
        try {
            return IpCidrMatcher.normalizeList(raw);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "IP 白名单格式错误：" + e.getMessage());
        }
    }

    /**
     * 密码是否正确（info 接口用于返回 passwordVerified，不抛异常）
     */
    public boolean matchesPassword(ShareLink link, String password) {
        if (link.getPasswordHash() == null || link.getPasswordHash().isBlank()) return true;
        return password != null && !password.isBlank()
                && passwordEncoder.matches(password, link.getPasswordHash());
    }

    /**
     * 校验范围：requiredScope 为空表示只读元信息
     */
    public void requireScope(ShareLink link, String requiredScope) {
        if (requiredScope == null || requiredScope.equals(link.getScope())) return;
        throw new BusinessException(ResultCode.PERMISSION_DENIED,
                "当前分享链接范围为 " + link.getScope() + "，不支持该操作（需要 " + requiredScope + "）");
    }

    /**
     * 消费一次使用次数（maxUses=0 表示不限，仅累计统计）
     */
    @Transactional
    public void consumeUse(ShareLink link) {
        int rows = shareLinkMapper.update(null, new LambdaUpdateWrapper<ShareLink>()
                .eq(ShareLink::getId, link.getId())
                .and(w -> w.isNull(ShareLink::getMaxUses)
                        .or().eq(ShareLink::getMaxUses, 0)
                        .or().apply("use_count < max_uses"))
                .setSql("use_count = use_count + 1"));
        if (rows == 0) {
            throw new BusinessException(ResultCode.QUOTA_EXCEEDED, "分享链接访问次数已用尽");
        }
    }

    /**
     * 吊销链接
     */
    @Transactional
    public void revoke(Long id, String operator) {
        ShareLink link = shareLinkMapper.selectById(id);
        if (link == null) {
            throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "分享链接不存在");
        }
        link.setRevoked(true);
        shareLinkMapper.updateById(link);
        log.info("吊销分享链接: id={}, operator={}", id, operator);
    }

    /**
     * 管理端分页列表
     */
    public PageResult<ShareLink> page(Long page, Long size, String keyword, String scope) {
        LambdaQueryWrapper<ShareLink> wrapper = new LambdaQueryWrapper<ShareLink>()
                .eq(scope != null && !scope.isBlank(), ShareLink::getScope,
                        scope == null ? null : scope.toUpperCase())
                .and(keyword != null && !keyword.isBlank(), w -> w
                        .like(ShareLink::getName, keyword)
                        .or().like(ShareLink::getToken, keyword))
                .orderByDesc(ShareLink::getId);
        Page<ShareLink> result = shareLinkMapper.selectPage(Page.of(page, size), wrapper);
        return new PageResult<>(result.getTotal(), result.getCurrent(), result.getSize(), result.getRecords());
    }
}
