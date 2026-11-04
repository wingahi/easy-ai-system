package com.wgh.rag.auth.config;

import com.wgh.rag.auth.security.OidcAuthenticationFilter;
import jakarta.servlet.DispatcherType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * 安全配置（外部 OIDC token 资源服务器 + CORS）
 */
@Slf4j
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final OidcAuthenticationFilter oidcAuthenticationFilter;

    /**
     * 允许跨域的来源白名单（逗号分隔）。默认仅本地开发前端；生产通过 WGH_CORS_ALLOWED_ORIGINS 显式配置。
     */
    @Value("${wgh.security.cors.allowed-origins:http://localhost:5173,http://127.0.0.1:5173}")
    private String allowedOrigins;

    /**
     * 是否允许浏览器自动携带凭证（Cookie）。Bearer Token 模式默认关闭。
     */
    @Value("${wgh.security.cors.allow-credentials:false}")
    private boolean allowCredentials;

    /**
     * 兜底过滤链：必须排在最末，否则会遮蔽带 securityMatcher 的专用过滤链
     * （如 PublicAccessSecurityConfig 的 /api/public/** 免登录链）。
     */
    @Bean
    @Order(2)
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // 禁用CSRF（外部 Bearer token 无状态鉴权）
                .csrf(AbstractHttpConfigurer::disable)
                // CORS
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                // 无状态Session
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 放行路径配置
                .authorizeHttpRequests(auth -> auth
                        // SSE 异步分发：鉴权已在初始 REQUEST 分发完成，
                        // ASYNC/ERROR 分发时 token 过滤器（OncePerRequestFilter）不再执行，
                        // 若不豁免会被 AuthorizationFilter 误判为匿名访问并打 ERROR 日志
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers(
                                "/api/auth/login",
                                "/api/auth/logout",
                                "/api/workflows/webhook/**",
                                "/doc.html",
                                "/webjars/**",
                                "/v3/api-docs/**",
                                "/swagger-resources/**",
                                "/favicon.ico",
                                "/actuator/**"
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                // 外部 OIDC token 资源服务器过滤器（JWKS 公钥验签）
                .addFilterBefore(oidcAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();

        // 公开访问链路（Widget 嵌入 / 分享页）需要被任意第三方站点跨域调用，
        // 因此保持来源开放，但强制不携带凭证（widget.js 使用 credentials: 'omit'）。
        source.registerCorsConfiguration("/api/public/**", buildConfig(List.of("*"), false));

        // 业务接口：来源白名单 + 默认不携带凭证，避免任意站点借浏览器凭证发起请求。
        List<String> origins = parseOrigins(allowedOrigins);
        boolean credentials = allowCredentials;
        if (credentials && origins.contains("*")) {
            log.warn("CORS 配置不安全：allowed-origins 为 * 时不能开启 allow-credentials，已强制关闭");
            credentials = false;
        }
        source.registerCorsConfiguration("/**", buildConfig(origins, credentials));

        return source;
    }

    private CorsConfiguration buildConfig(List<String> origins, boolean credentials) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(origins);
        config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(credentials);
        config.setMaxAge(3600L);
        return config;
    }

    private List<String> parseOrigins(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();
    }
}
