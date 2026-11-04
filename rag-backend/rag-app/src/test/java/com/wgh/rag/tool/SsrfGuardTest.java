package com.wgh.rag.tool;

import com.wgh.rag.tool.security.SsrfBlockedException;
import com.wgh.rag.tool.security.SsrfGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SsrfGuard 单元测试 —— 协议白名单、私网/元数据地址、DNS 重绑定、配置放行与管理员白名单
 */
@DisplayName("SsrfGuard 测试")
class SsrfGuardTest {

    private static final int MAX_REDIRECTS = 3;
    private static final long MAX_BYTES = 1024;

    /** 默认配置（禁止内网），使用真实解析器（测试内全部使用 IP 字面量或桩解析器） */
    private static SsrfGuard strictGuard() {
        return new SsrfGuard(false, "", MAX_REDIRECTS, MAX_BYTES);
    }

    /** 桩解析器：把域名解析到指定 IP，用于模拟 DNS 重绑定 */
    private static SsrfGuard stubResolverGuard(String host, String resolvedIp) {
        return new SsrfGuard(false, "", MAX_REDIRECTS, MAX_BYTES, h -> {
            if (host.equals(h)) {
                return new InetAddress[]{InetAddress.getByName(resolvedIp)};
            }
            throw new java.net.UnknownHostException(h);
        });
    }

    @Nested
    @DisplayName("协议白名单")
    class SchemeWhitelist {

        @Test
        @DisplayName("公网 http(s) URL 放行")
        void publicHttpsUrl_allowed() {
            SsrfGuard guard = stubResolverGuard("api.example.com", "93.184.216.34");

            assertThatCode(() -> guard.validate("https://api.example.com/v1/weather"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("file:// 协议被拒绝")
        void fileScheme_blocked() {
            assertThatThrownBy(() -> strictGuard().validate("file:///etc/passwd"))
                    .isInstanceOf(SsrfBlockedException.class)
                    .hasMessageContaining("仅允许 http/https");
        }

        @Test
        @DisplayName("ftp:// 协议被拒绝")
        void ftpScheme_blocked() {
            assertThatThrownBy(() -> strictGuard().validate("ftp://example.com/a.txt"))
                    .isInstanceOf(SsrfBlockedException.class)
                    .hasMessageContaining("仅允许 http/https");
        }

        @Test
        @DisplayName("gopher:// 协议被拒绝")
        void gopherScheme_blocked() {
            assertThatThrownBy(() -> strictGuard().validate("gopher://127.0.0.1:70/_"))
                    .isInstanceOf(SsrfBlockedException.class)
                    .hasMessageContaining("仅允许 http/https");
        }

        @Test
        @DisplayName("jar: 协议被拒绝")
        void jarScheme_blocked() {
            assertThatThrownBy(() -> strictGuard().validate("jar:http://example.com/a.jar!/x"))
                    .isInstanceOf(SsrfBlockedException.class)
                    .hasMessageContaining("仅允许 http/https");
        }

        @Test
        @DisplayName("缺少主机名被拒绝")
        void missingHost_blocked() {
            assertThatThrownBy(() -> strictGuard().validate("http:///path"))
                    .isInstanceOf(SsrfBlockedException.class)
                    .hasMessageContaining("缺少主机名");
        }
    }

    @Nested
    @DisplayName("私网 / 元数据地址")
    class PrivateAddresses {

        @Test
        @DisplayName("10/8 私网地址被拒绝")
        void tenNetwork_blocked() {
            assertThatThrownBy(() -> strictGuard().validate("http://10.1.2.3/"))
                    .isInstanceOf(SsrfBlockedException.class)
                    .hasMessageContaining("目标地址不允许访问");
        }

        @Test
        @DisplayName("172.16/12 私网地址被拒绝")
        void seventeenTwo_blocked() {
            assertThatThrownBy(() -> strictGuard().validate("http://172.16.0.10/"))
                    .isInstanceOf(SsrfBlockedException.class)
                    .hasMessageContaining("目标地址不允许访问");
        }

        @Test
        @DisplayName("192.168/16 私网地址被拒绝")
        void oneNineTwo_blocked() {
            assertThatThrownBy(() -> strictGuard().validate("http://192.168.1.1/"))
                    .isInstanceOf(SsrfBlockedException.class)
                    .hasMessageContaining("目标地址不允许访问");
        }

        @Test
        @DisplayName("127.0.0.1 回环地址被拒绝")
        void loopback_blocked() {
            assertThatThrownBy(() -> strictGuard().validate("http://127.0.0.1:8080/"))
                    .isInstanceOf(SsrfBlockedException.class)
                    .hasMessageContaining("目标地址不允许访问");
        }

        @Test
        @DisplayName("169.254.169.254 云元数据地址被拒绝")
        void metadata_blocked() {
            assertThatThrownBy(() -> strictGuard().validate("http://169.254.169.254/latest/meta-data/"))
                    .isInstanceOf(SsrfBlockedException.class)
                    .hasMessageContaining("目标地址不允许访问");
        }

        @Test
        @DisplayName("IPv6 回环 ::1 被拒绝")
        void ipv6Loopback_blocked() {
            assertThatThrownBy(() -> strictGuard().validate("http://[::1]:8080/"))
                    .isInstanceOf(SsrfBlockedException.class)
                    .hasMessageContaining("目标地址不允许访问");
        }

        @Test
        @DisplayName("IPv6 唯一本地 fc00::/7 被拒绝")
        void ipv6UniqueLocal_blocked() {
            assertThatThrownBy(() -> strictGuard().validate("http://[fd00::1]/"))
                    .isInstanceOf(SsrfBlockedException.class)
                    .hasMessageContaining("目标地址不允许访问");
        }

        @Test
        @DisplayName("CGNAT 100.64/10 被拒绝")
        void cgnat_blocked() {
            assertThatThrownBy(() -> strictGuard().validate("http://100.64.0.1/"))
                    .isInstanceOf(SsrfBlockedException.class)
                    .hasMessageContaining("目标地址不允许访问");
        }
    }

    @Nested
    @DisplayName("DNS 重绑定")
    class DnsRebinding {

        @Test
        @DisplayName("公网域名解析到内网 IP 时被拒绝")
        void publicHostResolvingToPrivate_blocked() {
            SsrfGuard guard = stubResolverGuard("evil.example.com", "10.0.0.5");

            assertThatThrownBy(() -> guard.validate("http://evil.example.com/steal"))
                    .isInstanceOf(SsrfBlockedException.class)
                    .hasMessageContaining("目标地址不允许访问");
        }

        @Test
        @DisplayName("域名解析到元数据地址时被拒绝")
        void hostResolvingToMetadata_blocked() {
            SsrfGuard guard = stubResolverGuard("meta.example.com", "169.254.169.254");

            assertThatThrownBy(() -> guard.validate("http://meta.example.com/"))
                    .isInstanceOf(SsrfBlockedException.class)
                    .hasMessageContaining("目标地址不允许访问");
        }

        @Test
        @DisplayName("解析失败时拒绝")
        void unresolvableHost_blocked() {
            SsrfGuard guard = new SsrfGuard(false, "", MAX_REDIRECTS, MAX_BYTES, h -> {
                throw new java.net.UnknownHostException(h);
            });

            assertThatThrownBy(() -> guard.validate("http://not-exist.example.com/"))
                    .isInstanceOf(SsrfBlockedException.class)
                    .hasMessageContaining("无法解析");
        }
    }

    @Nested
    @DisplayName("配置放行")
    class ConfigBypass {

        @Test
        @DisplayName("allow-private-network 打开时放行内网地址")
        void allowPrivateNetwork_allowsInternal() {
            SsrfGuard guard = new SsrfGuard(true, "", MAX_REDIRECTS, MAX_BYTES);

            assertThatCode(() -> guard.validate("http://192.168.1.20:8080/api"))
                    .doesNotThrowAnyException();
            assertThatCode(() -> guard.validate("http://127.0.0.1:9000/"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("管理员受信主机白名单放行解析到内网的域名")
        void trustedHost_allowsInternal() {
            SsrfGuard guard = new SsrfGuard(false, "internal.example.com,*.corp.example.com",
                    MAX_REDIRECTS, MAX_BYTES, h -> {
                if ("internal.example.com".equals(h) || "api.corp.example.com".equals(h)) {
                    return new InetAddress[]{InetAddress.getByName("10.0.0.8")};
                }
                throw new java.net.UnknownHostException(h);
            });

            assertThatCode(() -> guard.validate("http://internal.example.com/health"))
                    .doesNotThrowAnyException();
            assertThatCode(() -> guard.validate("http://api.corp.example.com/health"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("白名单之外的内网域名仍被拒绝")
        void nonTrustedHost_stillBlocked() {
            SsrfGuard guard = new SsrfGuard(false, "internal.example.com",
                    MAX_REDIRECTS, MAX_BYTES, h -> {
                if ("other.example.com".equals(h)) {
                    return new InetAddress[]{InetAddress.getByName("10.0.0.8")};
                }
                throw new java.net.UnknownHostException(h);
            });

            assertThatThrownBy(() -> guard.validate("http://other.example.com/"))
                    .isInstanceOf(SsrfBlockedException.class);
        }
    }

    @Nested
    @DisplayName("上限配置")
    class Limits {

        @Test
        @DisplayName("默认重定向与响应大小上限生效")
        void defaults() {
            SsrfGuard guard = strictGuard();

            assertThat(guard.getMaxRedirects()).isEqualTo(MAX_REDIRECTS);
            assertThat(guard.getMaxResponseBytes()).isEqualTo(MAX_BYTES);
        }

        @Test
        @DisplayName("非法上限回退到默认值")
        void invalidLimits_fallbackToDefaults() {
            SsrfGuard guard = new SsrfGuard(false, "", 0, 0);

            assertThat(guard.getMaxRedirects()).isEqualTo(SsrfGuard.DEFAULT_MAX_REDIRECTS);
            assertThat(guard.getMaxResponseBytes()).isEqualTo(SsrfGuard.DEFAULT_MAX_RESPONSE_BYTES);
        }
    }

    @Test
    @DisplayName("URI 形式校验与字符串形式一致")
    void validateUriOverload() {
        SsrfGuard guard = stubResolverGuard("api.example.com", "93.184.216.34");

        URI uri = guard.validate(URI.create("https://api.example.com/x"));
        assertThat(uri.getHost()).isEqualTo("api.example.com");
    }
}
