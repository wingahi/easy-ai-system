package com.wgh.rag.auth.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wgh.rag.common.entity.WghRole;
import com.wgh.rag.common.mapper.RoleMapper;
import com.wgh.rag.common.mapper.RolePermissionMapper;
import com.wgh.rag.common.security.LoginUser;
import io.jsonwebtoken.Claims;
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
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 外部 OIDC token 鉴权过滤器（资源服务器形态）。
 * <p>
 * 仅验证外部 IdP 签发的 token：从 {@code Authorization: Bearer} 取 token → JWKS 公钥验签
 * → 校验 iss / aud / exp / nbf。验不过直接 401。
 * <p>
 * 角色链路：token 角色 claim（默认 roles）→ 配置的角色映射 → 数据库 {@code wgh_role.role_code}
 * → {@code wgh_role_permission} 权限。token 不带角色或映射不到数据库角色时按默认角色（viewer）处理，
 * 不拒绝请求本身。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OidcAuthenticationFilter extends OncePerRequestFilter {

    private final OidcTokenVerifier tokenVerifier;
    private final RoleMapper roleMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final OidcProperties properties;

    private static final List<String> WHITE_LIST = List.of(
            // 登出仅清客户端 token，服务端无状态
            "/api/auth/logout",
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

        LoginUser.clear();

        String requestURI = request.getRequestURI();
        if (isWhiteList(requestURI)) {
            filterChain.doFilter(request, response);
            return;
        }

        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            writeUnauthorized(response, 1001, "未授权访问");
            return;
        }

        String token = authHeader.substring(7);
        Claims claims;
        try {
            claims = tokenVerifier.verify(token);
        } catch (TokenVerificationException e) {
            log.warn("外部 token 校验失败: {}", e.getMessage());
            writeUnauthorized(response, 1002, "Token已过期或无效");
            return;
        } catch (Exception e) {
            log.error("外部 token 校验异常", e);
            writeUnauthorized(response, 1002, "Token已过期或无效");
            return;
        }

        String username = claimAsText(claims, properties.getUsernameClaim());
        if (!StringUtils.hasText(username)) {
            username = claims.getSubject();
        }

        Long userId = claimAsLong(claims, "userId");

        Set<String> roles = resolveRoles(claims);
        Set<String> permissions = resolvePermissions(roles);

        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(userId);
        loginUser.setUsername(username);
        loginUser.setRoles(roles);
        loginUser.setPermissions(permissions);
        LoginUser.set(loginUser);

        request.setAttribute("userId", userId);
        request.setAttribute("username", username);

        Set<SimpleGrantedAuthority> authorities = new LinkedHashSet<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
        for (String role : roles) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
        }
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(loginUser, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        filterChain.doFilter(request, response);
    }

    /**
     * token 角色 claim → 配置映射 → 数据库 role_code。
     * 无角色 / 映射后均不在数据库时返回默认角色。
     */
    private Set<String> resolveRoles(Claims claims) {
        Set<String> candidates = new LinkedHashSet<>();
        Object rawClaim = claims.get(properties.getRolesClaim());
        for (String claimValue : normalizeClaimValues(rawClaim)) {
            String mapped = properties.getRoleMapping().getOrDefault(claimValue, claimValue);
            if (StringUtils.hasText(mapped)) {
                candidates.add(mapped.trim());
            }
        }

        String defaultRole = StringUtils.hasText(properties.getDefaultRole())
                ? properties.getDefaultRole() : LoginUser.DEFAULT_ROLE;
        if (candidates.isEmpty()) {
            return new LinkedHashSet<>(Set.of(defaultRole));
        }

        // 仅查 role_code，避免依赖不同环境的基础审计字段列（如 create_by）
        List<WghRole> existingRoles = roleMapper.selectList(
                new LambdaQueryWrapper<WghRole>()
                        .select(WghRole::getRoleCode)
                        .in(WghRole::getRoleCode, candidates));
        Set<String> matched = new LinkedHashSet<>();
        if (existingRoles != null) {
            for (WghRole role : existingRoles) {
                matched.add(role.getRoleCode());
            }
        }
        if (matched.isEmpty()) {
            matched.add(defaultRole);
        }
        return matched;
    }

    private Set<String> resolvePermissions(Set<String> roles) {
        if (roles.isEmpty()) {
            return new LinkedHashSet<>();
        }
        List<String> permCodes = rolePermissionMapper.selectPermCodesByRoleCodes(roles);
        return permCodes == null ? new LinkedHashSet<>() : new LinkedHashSet<>(permCodes);
    }

    private Collection<String> normalizeClaimValues(Object rawClaim) {
        Set<String> values = new LinkedHashSet<>();
        if (rawClaim instanceof Collection<?> collection) {
            for (Object item : collection) {
                if (item != null && StringUtils.hasText(item.toString())) {
                    values.add(item.toString().trim());
                }
            }
        } else if (rawClaim instanceof String text) {
            for (String part : text.split(",")) {
                if (StringUtils.hasText(part)) {
                    values.add(part.trim());
                }
            }
        } else if (rawClaim != null) {
            values.add(rawClaim.toString().trim());
        }
        return values;
    }

    private String claimAsText(Claims claims, String claimName) {
        if (!StringUtils.hasText(claimName)) {
            return null;
        }
        Object value = claims.get(claimName);
        return value == null ? null : value.toString();
    }

    private Long claimAsLong(Claims claims, String claimName) {
        Object value = claims.get(claimName);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && text.matches("\\d+")) {
            return Long.parseLong(text);
        }
        return null;
    }

    private boolean isWhiteList(String uri) {
        return WHITE_LIST.stream().anyMatch(uri::startsWith);
    }

    private void writeUnauthorized(HttpServletResponse response, int code, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":" + code + ",\"message\":\"" + message + "\",\"data\":null}");
    }
}
