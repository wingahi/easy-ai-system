package com.wgh.rag.publicaccess.config;

import com.wgh.rag.common.result.Result;
import com.wgh.rag.common.result.ResultCode;
import com.wgh.rag.auth.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

import jakarta.servlet.DispatcherType;

/**
 * 免登录分享 / 开放 API 专用安全过滤链。
 * <p>
 * 说明：
 * 1) 不修改既有 SecurityConfig（另一会话正在改造 auth），以 @Order(1) + securityMatcher 只接管
 *    /api/public/** 与 /api/open/**，其余路径仍走原过滤链（JWT 强制登录）。
 * 2) JwtAuthenticationFilter 是 @Component，Spring Boot 会把它自动注册为全局 Servlet Filter；
 *    该过滤器对无 Bearer 的请求直接返回 401，会导致免登录入口不可用。这里通过
 *    FilterRegistrationBean#setEnabled(false) 关闭它的 Servlet 自动注册，只保留其在原
 *    SecurityConfig 过滤链中的显式注册，不改变原有鉴权行为。
 */
@Configuration
@Order(1)
@RequiredArgsConstructor
public class PublicAccessSecurityConfig {

    private final ApiKeyAuthenticationFilter apiKeyAuthenticationFilter;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final CorsConfigurationSource corsConfigurationSource;

    @Bean
    @Order(1)
    public SecurityFilterChain publicAccessSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/api/public/**", "/api/open/**")
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // SSE 异步分发不再重复鉴权
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .anyRequest().permitAll()
                )
                .addFilterBefore(apiKeyAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(ex -> ex.authenticationEntryPoint((request, response, e) -> {
                    response.setStatus(HttpStatus.UNAUTHORIZED.value());
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write(
                            "{\"code\":" + ResultCode.UNAUTHORIZED.getCode()
                                    + ",\"message\":\"未授权访问\",\"data\":null}");
                }));
        return http.build();
    }

    /**
     * 关闭 JwtAuthenticationFilter 的 Servlet 自动注册，避免其拦截免登录入口。
     * 该过滤器仍在原 SecurityConfig 过滤链中生效（登录鉴权行为不变）。
     */
    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtFilterServletRegistration(
            JwtAuthenticationFilter filter) {
        FilterRegistrationBean<JwtAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
