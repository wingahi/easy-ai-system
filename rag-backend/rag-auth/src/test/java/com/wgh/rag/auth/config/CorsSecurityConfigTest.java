package com.wgh.rag.auth.config;

import com.wgh.rag.auth.security.OidcAuthenticationFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * CORS 配置回归测试：业务接口只放行配置内来源，公开访问链路保持跨域开放且不带凭证
 */
@DisplayName("CORS 配置")
class CorsSecurityConfigTest {

    private CorsConfigurationSource buildSource(String origins, boolean credentials) {
        SecurityConfig securityConfig = new SecurityConfig(mock(OidcAuthenticationFilter.class));
        ReflectionTestUtils.setField(securityConfig, "allowedOrigins", origins);
        ReflectionTestUtils.setField(securityConfig, "allowCredentials", credentials);
        return securityConfig.corsConfigurationSource();
    }

    private CorsConfiguration resolve(CorsConfigurationSource source, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        return source.getCorsConfiguration(request);
    }

    @Test
    @DisplayName("白名单来源放行，未命中来源拒绝")
    void allowedOriginOnly() {
        CorsConfiguration config = resolve(buildSource("https://app.example.com", false), "/api/chat/windows");

        assertThat(config.checkOrigin("https://app.example.com")).isEqualTo("https://app.example.com");
        assertThat(config.checkOrigin("https://evil.example.com")).isNull();
        assertThat(config.getAllowCredentials()).isFalse();
    }

    @Test
    @DisplayName("默认不携带凭证")
    void credentialsDisabledByDefault() {
        CorsConfiguration config = resolve(buildSource("https://app.example.com", false), "/api/models");

        assertThat(config.getAllowCredentials()).isFalse();
    }

    @Test
    @DisplayName("通配来源 + 开启凭证时强制关闭凭证")
    void wildcardWithCredentials_forcesCredentialsOff() {
        CorsConfiguration config = resolve(buildSource("*", true), "/api/models");

        assertThat(config.getAllowCredentials()).isFalse();
    }

    @Test
    @DisplayName("公开访问链路允许任意来源且不带凭证（Widget 跨域嵌入）")
    void publicAccessAllowsAnyOrigin() {
        CorsConfiguration config = resolve(buildSource("https://app.example.com", false), "/api/public/share/demo/stream");

        assertThat(config.checkOrigin("https://customer.example.org")).isNotNull();
        assertThat(config.getAllowCredentials()).isFalse();
    }
}
