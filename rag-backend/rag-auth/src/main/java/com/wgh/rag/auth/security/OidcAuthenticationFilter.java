package com.wgh.rag.auth.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wgh.rag.common.entity.WghRole;
import com.wgh.rag.common.entity.WghUser;
import com.wgh.rag.common.mapper.RoleMapper;
import com.wgh.rag.common.mapper.RolePermissionMapper;
import com.wgh.rag.common.mapper.UserMapper;
import com.wgh.rag.common.mapper.UserRoleMapper;
import com.wgh.rag.common.result.ResultCode;
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
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 认证过滤器（双认证并存）。
 * <p>
 * 按凭证 header 的 alg 区分来源：
 * <ul>
 *   <li>HS256 → 本地账号登录签发的内部会话凭证，走 {@link LocalTokenService#verify}；</li>
 *   <li>其他 → 外部 IdP 签发的 OIDC token，走 JWKS 公钥验签。</li>
 * </ul>
 * 外部验签算法白名单拒绝 HS256，本地验签只接受 HS256 且校验本地 issuer/audience，
 * 因此两种凭证无法互相冒充。
 * <p>
 * 角色链路：外部 token 角色 claim（默认 roles）→ 配置的角色映射 → 数据库
 * {@code wgh_role.role_code}；本地凭证按用户 ID 实时查数据库角色。权限统一由
 * {@code wgh_role_permission} 解析。token 不带角色或映射不到数据库角色时按默认角色（viewer）处理。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OidcAuthenticationFilter extends OncePerRequestFilter {

    private final OidcTokenVerifier tokenVerifier;
    private final LocalTokenService localTokenService;
    private final UserMapper userMapper;
    private final UserRoleMapper userRoleMapper;
    private final RoleMapper roleMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final OidcProperties properties;

    private static final List<String> WHITE_LIST = List.of(
            // 本地账号密码登录入口（登录本身不需要凭证）
            "/api/auth/login",
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
        if (localTokenService.isLocalToken(token)) {
            authenticateLocal(token, request, response, filterChain);
            return;
        }

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
     * 本地会话凭证认证：凭证签名通过后，用户状态/角色/权限全部实时查库。
     */
    private void authenticateLocal(String token,
                                   HttpServletRequest request,
                                   HttpServletResponse response,
                                   FilterChain filterChain) throws ServletException, IOException {
        Claims claims;
        try {
            claims = localTokenService.verify(token);
        } catch (TokenVerificationException e) {
            log.warn("本地会话凭证校验失败: {}", e.getMessage());
            writeUnauthorized(response, 1002, "会话凭证已失效，请重新登录");
            return;
        }

        Long userId = claimAsLong(claims, "userId");
        if (userId == null) {
            writeUnauthorized(response, 1002, "会话凭证无效");
            return;
        }
        WghUser user = userMapper.selectById(userId);
        if (user == null) {
            log.warn("本地会话凭证无效：用户不存在 userId={}", userId);
            writeUnauthorized(response, 1002, "账号不存在，请重新登录");
            return;
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            log.warn("本地会话凭证被拒：账号已禁用 username={}", user.getUsername());
            writeUnauthorized(response, 1002, "账号已禁用");
            return;
        }
        if (user.getExpireAt() != null && LocalDateTime.now().isAfter(user.getExpireAt())) {
            log.warn("本地会话凭证被拒：账号已过期 username={}", user.getUsername());
            writeUnauthorized(response, 1002, "账号已过期");
            return;
        }

        // 口令版本校验：改密/重置/禁用后旧凭证立即失效（外部 OIDC token 不进入本分支）
        Long tokenPv = claimAsLong(claims, LocalTokenService.CLAIM_PASSWORD_VERSION);
        int currentPv = user.getPasswordVersion() == null ? 1 : user.getPasswordVersion();
        if (tokenPv == null || tokenPv.intValue() != currentPv) {
            log.warn("本地会话凭证已失效：口令版本不一致 username={}, tokenPv={}, currentPv={}",
                    user.getUsername(), tokenPv, currentPv);
            writeUnauthorized(response, ResultCode.SESSION_INVALID.getCode(),
                    ResultCode.SESSION_INVALID.getMessage());
            return;
        }

        Set<String> roles = new LinkedHashSet<>();
        List<String> dbRoles = userRoleMapper.selectRoleCodesByUserId(userId);
        if (dbRoles != null) {
            roles.addAll(dbRoles);
        }
        if (roles.isEmpty()) {
            roles.add(LoginUser.DEFAULT_ROLE);
        }
        List<String> permCodes = rolePermissionMapper.selectPermCodesByUserId(userId);
        Set<String> permissions = permCodes == null
                ? new LinkedHashSet<>() : new LinkedHashSet<>(permCodes);

        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(userId);
        loginUser.setUsername(user.getUsername());
        loginUser.setRoles(roles);
        loginUser.setPermissions(permissions);
        loginUser.setPasswordMustChange(user.getPasswordMustChange() != null && user.getPasswordMustChange() == 1);
        LoginUser.set(loginUser);

        request.setAttribute("userId", userId);
        request.setAttribute("username", user.getUsername());

        Set<SimpleGrantedAuthority> authorities = new LinkedHashSet<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
        for (String role : roles) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
        }
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(loginUser, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // 首登强制改密：除改密、登出与读取类请求外，其余（写）接口一律拒绝
        if (Boolean.TRUE.equals(loginUser.getPasswordMustChange()) && isPasswordChangeRequired(request)) {
            log.warn("账号未完成首次改密，拒绝写请求 username={}, {} {}", user.getUsername(),
                    request.getMethod(), request.getRequestURI());
            writeError(response, HttpServletResponse.SC_FORBIDDEN,
                    ResultCode.PASSWORD_CHANGE_REQUIRED.getCode(),
                    ResultCode.PASSWORD_CHANGE_REQUIRED.getMessage());
            return;
        }

        filterChain.doFilter(request, response);
    }

    /**
     * 是否属于"必须先改密"拦截范围：非只读请求且不是改密/登出接口
     */
    private boolean isPasswordChangeRequired(HttpServletRequest request) {
        String method = request.getMethod();
        if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)
                || "OPTIONS".equalsIgnoreCase(method)) {
            return false;
        }
        String uri = request.getRequestURI();
        return !uri.startsWith("/api/auth/password") && !uri.startsWith("/api/auth/logout");
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
        writeError(response, HttpServletResponse.SC_UNAUTHORIZED, code, message);
    }

    private void writeError(HttpServletResponse response, int httpStatus, int code, String message)
            throws IOException {
        response.setStatus(httpStatus);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":" + code + ",\"message\":\"" + message + "\",\"data\":null}");
    }
}
