package com.wgh.rag.publicaccess.service;

import com.wgh.rag.publicaccess.config.PublicAccessProperties;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 客户端真实 IP 解析。
 *
 * <p>默认只使用 TCP 对端地址。仅当对端地址命中 {@code wgh.public-access.trusted-proxies}
 * 时，才从右向左解析 {@code X-Forwarded-For}，跳过可信代理，取第一个非可信地址；
 * 没有 XFF 时才使用 {@code X-Real-IP}。这样避免客户端直接伪造代理头绕过 IP 白名单。</p>
 */
@Slf4j
@Component
public class ClientIpResolver {

    private final PublicAccessProperties properties;
    private volatile List<IpCidrMatcher> trustedProxies;

    public ClientIpResolver(PublicAccessProperties properties) {
        this.properties = properties;
    }

    public String resolve(HttpServletRequest request) {
        String remoteAddress = IpCidrMatcher.canonical(request.getRemoteAddr());
        if (!isTrustedProxy(remoteAddress)) {
            return remoteAddress;
        }
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            String[] parts = forwardedFor.split(",");
            for (int i = parts.length - 1; i >= 0; i--) {
                String candidate = IpCidrMatcher.canonical(parts[i].trim());
                if (candidate.isEmpty()) {
                    continue;
                }
                if (!isTrustedProxy(candidate)) {
                    return candidate;
                }
            }
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return IpCidrMatcher.canonical(realIp.trim());
        }
        return remoteAddress;
    }

    public boolean isTrustedProxy(String ip) {
        if (ip == null || ip.isBlank()) {
            return false;
        }
        for (IpCidrMatcher matcher : trustedProxyMatchers()) {
            if (matcher.matches(ip)) {
                return true;
            }
        }
        return false;
    }

    private List<IpCidrMatcher> trustedProxyMatchers() {
        List<IpCidrMatcher> cached = trustedProxies;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (trustedProxies == null) {
                List<IpCidrMatcher> matchers = new ArrayList<>();
                List<String> configured = properties.getTrustedProxies();
                if (configured != null) {
                    for (String raw : configured) {
                        try {
                            matchers.add(IpCidrMatcher.parse(raw));
                        } catch (IllegalArgumentException e) {
                            log.warn("忽略非法的可信代理配置 [{}]: {}", raw, e.getMessage());
                        }
                    }
                }
                trustedProxies = matchers;
            }
            return trustedProxies;
        }
    }
}
