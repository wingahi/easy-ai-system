package com.wgh.rag.auth.security;

import com.wgh.rag.common.entity.WghUser;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.mapper.RolePermissionMapper;
import com.wgh.rag.common.mapper.UserMapper;
import com.wgh.rag.common.mapper.UserRoleMapper;
import com.wgh.rag.common.security.LoginUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * JWT 鉴权过滤器 —— 解析 JWT → 从 DB 加载 roles/permissions → 填 LoginUser 上下文 + SecurityContext
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtils jwtUtils;
    private final UserMapper userMapper;
    private final UserRoleMapper userRoleMapper;
    private final RolePermissionMapper rolePermissionMapper;

    private static final List<String> WHITE_LIST = Arrays.asList(
            "/api/auth/login",
            "/api/auth/captcha",
            // OIDC 授权入口免鉴权（回调端点也在 /api/auth/oidc 下，已被 SecurityConfig 的 /api/auth/** 放行覆盖）
            "/api/auth/oidc/",
            // 工作流 Webhook 触发入口：凭路径中的 token 鉴权，免登录
            "/api/workflows/webhook/",
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

        if (isWhiteList(requestURI)) {
            filterChain.doFilter(request, response);
            return;
        }

        // 清理上一轮 ThreadLocal（异步/SSE 分发不会走到这里，主链路清理）
        LoginUser.clear();

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

        try {
            Long userId = jwtUtils.getUserIdFromToken(token);
            String username = jwtUtils.getUsernameFromToken(token);

            // 从 DB 加载 roles + permissions（幂等，无则空集合）
            WghUser user = userMapper.selectById(userId);
            if (user != null && user.getStatus() != null && user.getStatus() != 1) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":1003,\"message\":\"账号已被禁用\",\"data\":null}");
                return;
            }

            Set<String> roles = new HashSet<>();
            if (user != null) {
                List<String> roleCodes = userRoleMapper.selectRoleCodesByUserId(userId);
                if (roleCodes != null) roles.addAll(roleCodes);
            }
            // 数据库查不到角色时回退 token 中的 roles claim，仍为空则按默认普通用户角色处理
            if (roles.isEmpty()) {
                roles.addAll(jwtUtils.getRolesFromToken(token));
            }
            if (roles.isEmpty()) {
                roles.add(LoginUser.DEFAULT_ROLE);
            }
            // 权限始终按数据库 role → permission 匹配结果加载，不做硬编码特权绕过
            List<String> permCodes = rolePermissionMapper.selectPermCodesByRoleCodes(roles);
            Set<String> permissions = permCodes == null ? new HashSet<>() : new HashSet<>(permCodes);

            LoginUser loginUser = new LoginUser();
            loginUser.setUserId(userId);
            loginUser.setUsername(username);
            loginUser.setRoles(roles);
            loginUser.setPermissions(permissions);
            LoginUser.set(loginUser);

            request.setAttribute("userId", userId);
            request.setAttribute("username", username);

            Set<SimpleGrantedAuthorityCompat> authorities = new HashSet<>();
            authorities.add(new SimpleGrantedAuthorityCompat("ROLE_USER"));
            for (String r : roles) authorities.add(new SimpleGrantedAuthorityCompat("ROLE_" + r));
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(loginUser, null, authorities);
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (Exception e) {
            log.error("解析Token失败", e);
        }

        filterChain.doFilter(request, response);
    }

    private boolean isWhiteList(String uri) {
        return WHITE_LIST.stream().anyMatch(uri::startsWith);
    }

    /** 兼容内部 Authority 实现，避免对 Spring Security GrantedAuthority 的硬依赖增加模块耦合 */
    private static class SimpleGrantedAuthorityCompat implements org.springframework.security.core.GrantedAuthority {
        private final String role;
        SimpleGrantedAuthorityCompat(String role) { this.role = role; }
        @Override public String getAuthority() { return role; }
    }
}
