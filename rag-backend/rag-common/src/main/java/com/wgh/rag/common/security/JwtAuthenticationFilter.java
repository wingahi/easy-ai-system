package com.wgh.rag.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * JWT 鉴权过滤器
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtils jwtUtils;

    /**
     * 白名单路径（不需要鉴权）
     * 注意：分享接口 /api/share/ 与 /api/chat/share/ 已移除 —— 分享页要求登录，userIdentifier 取当前用户名
     */
    private static final List<String> WHITE_LIST = Arrays.asList(
            "/api/auth/login",
            "/api/auth/captcha",
            "/doc.html",
            "/webjars/",
            "/v3/api-docs",
            "/swagger-resources",
            "/favicon.ico",
            "/actuator"
    );

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        
        String requestURI = request.getRequestURI();

        // 白名单路径放行
        if (isWhiteList(requestURI)) {
            filterChain.doFilter(request, response);
            return;
        }

        // 分享接口需要登录（JWT 鉴权）—— 不再放行 /api/share 与 /api/chat/share
        // 历史会话按当前登录用户名查询，与 shareToken 解耦

        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":1001,\"message\":\"未授权访问\",\"data\":null}");
            return;
        }

        String token = authHeader.substring(7);
        if (!jwtUtils.validateToken(token)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":1002,\"message\":\"Token已过期或无效\",\"data\":null}");
            return;
        }

        // 将用户信息放入request attribute，供后续Controller使用
        try {
            Long userId = jwtUtils.getUserIdFromToken(token);
            String username = jwtUtils.getUsernameFromToken(token);
            request.setAttribute("userId", userId);
            request.setAttribute("username", username);

            // 关键：把认证主体放入 SecurityContext，Spring Security 的 .authenticated() 才能放行
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            username, null, Set.of(new SimpleGrantedAuthority("ROLE_USER")));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (Exception e) {
            log.error("解析Token失败", e);
        }

        filterChain.doFilter(request, response);
    }

    private boolean isWhiteList(String uri) {
        return WHITE_LIST.stream().anyMatch(uri::startsWith);
    }
}
