package com.wgh.rag.publicaccess.service;

import java.net.URI;
import java.util.Locale;

/**
 * 域名白名单匹配。
 *
 * <p>支持精确域名（example.com）、单层通配子域名（*.example.com）与全通配（*）。
 * 通配子域名只匹配子域，不匹配裸域。</p>
 */
public final class DomainWhitelistMatcher {

    private DomainWhitelistMatcher() {
    }

    /**
     * 从 Origin / Referer 中提取主机名。
     *
     * @return 主机名（小写、去掉末尾点）；缺失、非法或 "null" 返回 null
     */
    public static String extractHost(String urlOrHost) {
        if (urlOrHost == null || urlOrHost.isBlank() || "null".equalsIgnoreCase(urlOrHost.trim())) {
            return null;
        }
        String value = urlOrHost.trim();
        if (value.startsWith("*.")) {
            String wildcard = value.substring(2);
            int separator = wildcard.indexOf('/');
            if (separator > -1) {
                wildcard = wildcard.substring(0, separator);
            }
            return "*." + wildcard.toLowerCase(Locale.ROOT).replaceAll("\\.$", "");
        }
        if (!value.contains("://")) {
            value = "http://" + value;
        }
        try {
            URI uri = new URI(value);
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return null;
            }
            return host.toLowerCase(Locale.ROOT).replaceAll("\\.$", "");
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 主机名是否命中白名单。
     */
    public static boolean matchesHost(String host, String whitelist) {
        if (host == null || host.isBlank() || whitelist == null || whitelist.isBlank()) {
            return false;
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT).replaceAll("\\.$", "");
        for (String raw : whitelist.split("[,;\\s]+")) {
            if (raw.isBlank()) {
                continue;
            }
            String pattern = extractHost(raw);
            if (pattern == null) {
                continue;
            }
            if ("*".equals(pattern)) {
                return true;
            }
            if (pattern.startsWith("*.")) {
                String suffix = pattern.substring(1);
                if (normalizedHost.length() > suffix.length() && normalizedHost.endsWith(suffix)) {
                    return true;
                }
            } else if (normalizedHost.equals(pattern)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 优先使用 Origin，其次 Referer；两者都缺失时返回 false。
     */
    public static boolean matchesOriginOrReferer(String origin, String referer, String whitelist) {
        String host = extractHost(origin);
        if (host == null) {
            host = extractHost(referer);
        }
        return host != null && matchesHost(host, whitelist);
    }

    /**
     * 返回用于错误提示的来源主机；缺失时返回 null。
     */
    public static String resolveOriginHost(String origin, String referer) {
        String host = extractHost(origin);
        return host != null ? host : extractHost(referer);
    }
}
