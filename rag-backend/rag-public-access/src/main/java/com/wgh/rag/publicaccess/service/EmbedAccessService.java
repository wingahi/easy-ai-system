package com.wgh.rag.publicaccess.service;

import com.wgh.rag.publicaccess.config.PublicAccessProperties;
import com.wgh.rag.publicaccess.entity.ShareLink;
import com.wgh.rag.publicaccess.exception.PublicAccessException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Widget 嵌入授权校验（服务端执行）。
 *
 * <p>Widget 所在宿主页面会先请求 {@code /api/public/share/{token}/embed/authorize}，
 * 浏览器自动携带真实 Origin；服务端按域名 / IP 白名单校验后签发短期 grant。
 * 后续接口调用需携带 {@code X-Embed-Grant}，避免依赖可伪造的客户端头。</p>
 */
@Slf4j
@Component
public class EmbedAccessService {

    private static final String GRANT_PREFIX = "wgh:pa:embed:grant:";

    private final PublicAccessStore store;
    private final PublicAccessProperties properties;
    private final ClientIpResolver clientIpResolver;

    public EmbedAccessService(PublicAccessStore store, PublicAccessProperties properties,
                              ClientIpResolver clientIpResolver) {
        this.store = store;
        this.properties = properties;
        this.clientIpResolver = clientIpResolver;
    }

    /**
     * 为合法嵌入来源签发 grant；未授权来源抛出可读异常。
     */
    public String authorize(ShareLink link, HttpServletRequest request) {
        String clientIp = clientIpResolver.resolve(request);
        if (!Boolean.TRUE.equals(link.getEmbedEnabled())) {
            throw PublicAccessException.forbidden("该分享链接未开启 Widget 嵌入授权，请先在管理端开启并配置白名单");
        }
        if (!isOriginAllowed(link, request.getHeader("Origin"), request.getHeader("Referer"), clientIp)) {
            String host = DomainWhitelistMatcher.resolveOriginHost(
                    request.getHeader("Origin"), request.getHeader("Referer"));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("clientIp", clientIp);
            data.put("origin", host);
            if (host == null) {
                throw PublicAccessException.forbidden(
                        "嵌入请求缺少 Origin/Referer，按默认策略拒绝；请从白名单域名页面加载 Widget", data);
            }
            throw PublicAccessException.forbidden("来源 " + host + " 不在该分享链接的嵌入白名单内", data);
        }
        String grant = "eg_" + UUID.randomUUID().toString().replace("-", "");
        int ttlMinutes = Math.max(1, properties.getEmbed().getGrantTtlMinutes());
        store.setString(grantKey(link.getToken(), grant), clientIp, System.currentTimeMillis(),
                Duration.ofMinutes(ttlMinutes));
        log.info("签发 Widget 嵌入授权: token={}, grant={}, origin={}, ip={}",
                com.wgh.rag.common.log.LogSanitizer.maskSecret(link.getToken()),
                com.wgh.rag.common.log.LogSanitizer.maskSecret(grant),
                DomainWhitelistMatcher.resolveOriginHost(
                        request.getHeader("Origin"), request.getHeader("Referer")), clientIp);
        return grant;
    }

    /**
     * 校验后续接口调用是否允许。
     *
     * <p>非嵌入模式（普通分享页直连）不受嵌入白名单影响；带嵌入标识但无有效 grant 的请求拒绝。</p>
     */
    public void validate(ShareLink link, HttpServletRequest request) {
        String grant = request.getHeader("X-Embed-Grant");
        boolean embedMode = grant != null
                || "1".equals(request.getHeader("X-Embed-Mode"))
                || "iframe".equals(request.getHeader("Sec-Fetch-Dest"))
                || "1".equals(request.getParameter("embed"));

        if (!Boolean.TRUE.equals(link.getEmbedEnabled())) {
            if (embedMode) {
                throw PublicAccessException.forbidden("该分享链接未开启 Widget 嵌入授权，禁止嵌入访问");
            }
            return;
        }
        if (grant != null) {
            String clientIp = clientIpResolver.resolve(request);
            String boundIp = store.getString(grantKey(link.getToken(), grant), System.currentTimeMillis())
                    .orElse(null);
            if (boundIp == null || !boundIp.equals(clientIp)) {
                throw PublicAccessException.forbidden("Widget 嵌入授权无效或已过期，请重新加载页面");
            }
            return;
        }
        if (embedMode) {
            throw PublicAccessException.forbidden("缺少 Widget 嵌入授权，请通过 widget.js 加载分享页");
        }
        String origin = request.getHeader("Origin");
        String referer = request.getHeader("Referer");
        if ((origin != null && !origin.isBlank()) || (referer != null && !referer.isBlank())) {
            String originHost = DomainWhitelistMatcher.extractHost(origin);
            String refererHost = DomainWhitelistMatcher.extractHost(referer);
            String requestHost = DomainWhitelistMatcher.extractHost(request.getHeader("Host"));
            // 普通分享页直连：同源 XHR 没有 Origin，Referer 指向分享页自身，不受嵌入白名单限制
            if (originHost == null && refererHost != null && requestHost != null
                    && refererHost.equals(requestHost)) {
                return;
            }
            String clientIp = clientIpResolver.resolve(request);
            if (!isOriginAllowed(link, origin, referer, clientIp)) {
                String host = DomainWhitelistMatcher.resolveOriginHost(origin, referer);
                throw PublicAccessException.forbidden("来源 " + host + " 不在该分享链接的嵌入白名单内");
            }
        }
    }

    public int grantTtlSeconds() {
        return Math.max(1, properties.getEmbed().getGrantTtlMinutes()) * 60;
    }

    private boolean isOriginAllowed(ShareLink link, String origin, String referer, String clientIp) {
        String domainWhitelist = link.getEmbedOriginWhitelist();
        String ipWhitelist = link.getEmbedIpWhitelist();
        boolean hasDomain = domainWhitelist != null && !domainWhitelist.isBlank();
        boolean hasIp = ipWhitelist != null && !ipWhitelist.isBlank();
        if (!hasDomain && !hasIp) {
            // 已开启嵌入但未配置任何白名单：允许任意来源（配置语义明确，文档已说明）
            return true;
        }
        boolean domainMatched = hasDomain
                && DomainWhitelistMatcher.matchesOriginOrReferer(origin, referer, domainWhitelist);
        boolean ipMatched = false;
        if (hasIp) {
            try {
                for (IpCidrMatcher matcher : IpCidrMatcher.parseList(ipWhitelist)) {
                    if (matcher.matches(clientIp)) {
                        ipMatched = true;
                        break;
                    }
                }
            } catch (IllegalArgumentException e) {
                log.warn("分享链接 {} 的嵌入 IP 白名单配置非法: {}",
                        com.wgh.rag.common.log.LogSanitizer.maskSecret(link.getToken()), e.getMessage());
            }
        }
        return domainMatched || ipMatched;
    }

    private String grantKey(String token, String grant) {
        return GRANT_PREFIX + token + ":" + grant;
    }
}
