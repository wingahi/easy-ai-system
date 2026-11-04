package com.wgh.rag.tool.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * SSRF 防护组件 —— 出站请求统一的 URL / 主机 / IP 校验。
 *
 * <p>校验规则：
 * <ol>
 *   <li>协议白名单：仅允许 {@code http}/{@code https}，拒绝 {@code file}/{@code ftp}/{@code gopher}/{@code jar} 等；</li>
 *   <li>目标主机：拒绝回环、链路本地（含云元数据 {@code 169.254.169.254}）、私有网段（10/8、172.16/12、192.168/16）、
 *   CGNAT（100.64/10）、IPv6 唯一本地（fc00::/7）与 any/multicast 地址；</li>
 *   <li>域名解析后逐个校验解析出的 IP，降低 DNS 重绑定风险（每跳重定向都会重新解析并校验）；</li>
 *   <li>管理员可显式配置受信主机白名单（{@code wgh.tool.ssrf.trusted-hosts}）或整体放开内网访问
 *   （{@code wgh.tool.ssrf.allow-private-network}，仅限内网集成场景，开启即承担风险）。</li>
 * </ol>
 *
 * <p>重定向与响应大小上限由调用方（{@link com.wgh.rag.tool.executor.HttpToolExecutor}）按本组件配置逐跳执行。
 */
@Slf4j
@Component
public class SsrfGuard {

    /** 默认最大重定向跳数 */
    public static final int DEFAULT_MAX_REDIRECTS = 3;
    /** 默认最大响应字节数（2MB） */
    public static final long DEFAULT_MAX_RESPONSE_BYTES = 2L * 1024 * 1024;

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    /** 便于测试替换的域名解析器 */
    @FunctionalInterface
    public interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final boolean allowPrivateNetwork;
    private final Set<String> trustedHosts;
    private final int maxRedirects;
    private final long maxResponseBytes;
    private final HostResolver resolver;

    public SsrfGuard(@Value("${wgh.tool.ssrf.allow-private-network:false}") boolean allowPrivateNetwork,
                     @Value("${wgh.tool.ssrf.trusted-hosts:}") String trustedHosts,
                     @Value("${wgh.tool.ssrf.max-redirects:" + DEFAULT_MAX_REDIRECTS + "}") int maxRedirects,
                     @Value("${wgh.tool.ssrf.max-response-bytes:" + DEFAULT_MAX_RESPONSE_BYTES + "}") long maxResponseBytes) {
        this(allowPrivateNetwork, trustedHosts, maxRedirects, maxResponseBytes, InetAddress::getAllByName);
    }

    public SsrfGuard(boolean allowPrivateNetwork, String trustedHosts,
                     int maxRedirects, long maxResponseBytes, HostResolver resolver) {
        this.allowPrivateNetwork = allowPrivateNetwork;
        this.trustedHosts = parseHosts(trustedHosts);
        this.maxRedirects = maxRedirects <= 0 ? DEFAULT_MAX_REDIRECTS : maxRedirects;
        this.maxResponseBytes = maxResponseBytes <= 0 ? DEFAULT_MAX_RESPONSE_BYTES : maxResponseBytes;
        this.resolver = resolver == null ? InetAddress::getAllByName : resolver;
    }

    public int getMaxRedirects() {
        return maxRedirects;
    }

    public long getMaxResponseBytes() {
        return maxResponseBytes;
    }

    /**
     * 校验并返回可用的目标地址；不通过时抛出 {@link SsrfBlockedException}。
     */
    public URI validate(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new SsrfBlockedException("URL 为空");
        }
        URI uri;
        try {
            uri = URI.create(rawUrl.trim());
        } catch (IllegalArgumentException e) {
            throw new SsrfBlockedException("URL 格式非法", e);
        }
        return validate(uri);
    }

    public URI validate(URI uri) {
        if (uri == null) {
            throw new SsrfBlockedException("URL 为空");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!ALLOWED_SCHEMES.contains(scheme)) {
            throw new SsrfBlockedException("仅允许 http/https 协议，当前为: " + (scheme.isEmpty() ? "<空>" : scheme));
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new SsrfBlockedException("URL 缺少主机名");
        }
        if (isTrustedHost(host)) {
            log.debug("SSRF 校验命中管理员受信主机白名单: {}", host);
            return uri;
        }
        if (allowPrivateNetwork) {
            return uri;
        }
        String resolveHost = stripIpv6Brackets(host);
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(resolveHost);
        } catch (UnknownHostException e) {
            throw new SsrfBlockedException("目标主机无法解析: " + host, e);
        }
        if (addresses == null || addresses.length == 0) {
            throw new SsrfBlockedException("目标主机无法解析: " + host);
        }
        for (InetAddress address : addresses) {
            if (isBlockedAddress(address)) {
                throw new SsrfBlockedException("目标地址不允许访问: " + host + " -> " + address.getHostAddress());
            }
        }
        return uri;
    }

    /**
     * 判定单个解析结果是否属于禁止访问的地址段。
     */
    public static boolean isBlockedAddress(InetAddress address) {
        if (address == null) {
            return true;
        }
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        if (address instanceof Inet4Address) {
            byte[] raw = address.getAddress();
            int first = raw[0] & 0xff;
            int second = raw[1] & 0xff;
            // 0.0.0.0/8
            if (first == 0) {
                return true;
            }
            // CGNAT 100.64.0.0/10
            if (first == 100 && second >= 64 && second <= 127) {
                return true;
            }
            // 云元数据 / 链路本地 169.254.0.0/16（isLinkLocalAddress 已覆盖，这里显式兜底）
            if (first == 169 && second == 254) {
                return true;
            }
            // 127.0.0.0/8
            if (first == 127) {
                return true;
            }
            return false;
        }
        byte[] raw = address.getAddress();
        // IPv6 唯一本地 fc00::/7
        return (raw[0] & 0xfe) == 0xfc;
    }

    private boolean isTrustedHost(String host) {
        if (trustedHosts.isEmpty()) {
            return false;
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        if (trustedHosts.contains(normalized)) {
            return true;
        }
        for (String pattern : trustedHosts) {
            if (pattern.startsWith("*.") && normalized.endsWith(pattern.substring(1))) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> parseHosts(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        Set<String> hosts = new LinkedHashSet<>();
        Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .forEach(hosts::add);
        return Set.copyOf(hosts);
    }

    private static String stripIpv6Brackets(String host) {
        if (host.startsWith("[") && host.endsWith("]")) {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }
}
