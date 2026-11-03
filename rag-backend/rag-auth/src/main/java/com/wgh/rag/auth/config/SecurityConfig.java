package com.wgh.rag.auth.config;

import com.wgh.rag.auth.security.OidcAuthenticationFilter;
import jakarta.servlet.DispatcherType;
import lombok.RequiredArgsConstructor;
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
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final OidcAuthenticationFilter oidcAuthenticationFilter;

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
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("*"));
        config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
