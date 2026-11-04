package com.wgh.rag.auth.security;

import com.wgh.rag.auth.entity.WghUser;
import com.wgh.rag.auth.mapper.RoleMapper;
import com.wgh.rag.auth.mapper.RolePermissionMapper;
import com.wgh.rag.auth.mapper.UserMapper;
import com.wgh.rag.auth.mapper.UserRoleMapper;
import com.wgh.rag.common.result.ResultCode;
import com.wgh.rag.common.security.LoginUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 本地会话语义测试（补充 {@link OidcResourceServerTest} 未覆盖的本地凭证分支）：
 * 口令版本 pv 失效、账号状态、首登强制改密、白名单与实时角色/权限解析。
 */
@DisplayName("本地会话凭证过滤器分支")
class OidcAuthenticationFilterLocalSessionTest {

    private static final String JWT_SECRET = "unit-test-local-secret-0123456789abcdef0123456789";

    private LocalTokenService localTokenService;
    private UserMapper userMapper;
    private UserRoleMapper userRoleMapper;
    private RolePermissionMapper rolePermissionMapper;
    private OidcAuthenticationFilter filter;
    private LoginUser capturedLoginUser;

    @BeforeEach
    void setUp() {
        LocalAuthProperties local = new LocalAuthProperties();
        local.setJwtSecret(JWT_SECRET);
        localTokenService = new LocalTokenService(local);

        OidcProperties oidc = new OidcProperties();
        oidc.setIssuer("https://idp.example.com");
        oidc.setAudience("wgh-rag");
        oidc.setJwksUri("https://idp.example.com/jwks");

        userMapper = mock(UserMapper.class);
        userRoleMapper = mock(UserRoleMapper.class);
        rolePermissionMapper = mock(RolePermissionMapper.class);
        filter = new OidcAuthenticationFilter(
                new OidcTokenVerifier(oidc, new JwksProvider(oidc)),
                localTokenService, userMapper, userRoleMapper, mock(RoleMapper.class),
                rolePermissionMapper, oidc);
    }

    @AfterEach
    void tearDown() {
        LoginUser.clear();
    }

    // ===== 口令版本 pv =====

    @Test
    @DisplayName("pv 与数据库不一致（改密后旧凭证）→ 401 / code 1007")
    void passwordVersionMismatchInvalidatesSession() throws Exception {
        WghUser user = activeUser();
        user.setPasswordVersion(2);
        when(userMapper.selectById(1L)).thenReturn(user);
        when(userRoleMapper.selectRoleCodesByUserId(1L)).thenReturn(List.of("admin"));

        MockHttpServletResponse response = doFilter("GET", "/api/kb", token("admin", 1L, 1));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"code\":" + ResultCode.SESSION_INVALID.getCode());
        assertThat(capturedLoginUser).isNull();
    }

    @Test
    @DisplayName("pv 一致：认证通过，角色/权限按数据库实时解析")
    void matchingPasswordVersionPasses() throws Exception {
        WghUser user = activeUser();
        user.setPasswordVersion(3);
        when(userMapper.selectById(1L)).thenReturn(user);
        when(userRoleMapper.selectRoleCodesByUserId(1L)).thenReturn(List.of("editor"));
        when(rolePermissionMapper.selectPermCodesByUserId(1L)).thenReturn(List.of("kb:read", "kb:write"));

        MockHttpServletResponse response = doFilter("GET", "/api/kb", token("stale-name", 1L, 3, "admin"));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(capturedLoginUser).isNotNull();
        // 用户名以数据库为准，不信任凭证中的 subject
        assertThat(capturedLoginUser.getUsername()).isEqualTo("admin");
        assertThat(capturedLoginUser.getRoles()).containsExactly("editor");
        assertThat(capturedLoginUser.getPermissions()).containsExactly("kb:read", "kb:write");
    }

    @Test
    @DisplayName("pv 缺失（null）按 1 处理：与数据库默认版本一致时通过")
    void nullPasswordVersionFallsBackToOne() throws Exception {
        WghUser user = activeUser();
        user.setPasswordVersion(null);
        when(userMapper.selectById(1L)).thenReturn(user);
        when(userRoleMapper.selectRoleCodesByUserId(1L)).thenReturn(List.of("admin"));

        MockHttpServletResponse response = doFilter("GET", "/api/kb", token("admin", 1L, 1));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(capturedLoginUser).isNotNull();
    }

    // ===== 账号状态 =====

    @Test
    @DisplayName("用户不存在 → 401 / code 1002")
    void missingUserRejected() throws Exception {
        when(userMapper.selectById(1L)).thenReturn(null);

        MockHttpServletResponse response = doFilter("GET", "/api/kb", token("admin", 1L, 1));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"code\":" + ResultCode.TOKEN_EXPIRED.getCode());
    }

    @Test
    @DisplayName("账号禁用 → 401 / code 1002")
    void disabledUserRejected() throws Exception {
        WghUser user = activeUser();
        user.setStatus(0);
        when(userMapper.selectById(1L)).thenReturn(user);

        MockHttpServletResponse response = doFilter("GET", "/api/kb", token("admin", 1L, 1));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"code\":" + ResultCode.TOKEN_EXPIRED.getCode());
        assertThat(capturedLoginUser).isNull();
    }

    @Test
    @DisplayName("账号过期 → 401 / code 1002")
    void expiredUserRejected() throws Exception {
        WghUser user = activeUser();
        user.setExpireAt(LocalDateTime.now().minusMinutes(1));
        when(userMapper.selectById(1L)).thenReturn(user);

        MockHttpServletResponse response = doFilter("GET", "/api/kb", token("admin", 1L, 1));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"code\":" + ResultCode.TOKEN_EXPIRED.getCode());
    }

    @Test
    @DisplayName("本地凭证被篡改 → 401 / code 1002")
    void tamperedLocalTokenRejected() throws Exception {
        String tampered = token("admin", 1L, 1);
        tampered = tampered.substring(0, tampered.length() - 2) + "xy";

        MockHttpServletResponse response = doFilter("GET", "/api/kb", tampered);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"code\":" + ResultCode.TOKEN_EXPIRED.getCode());
        assertThat(capturedLoginUser).isNull();
    }

    // ===== 首登强制改密 =====

    @Test
    @DisplayName("首登未改密：写请求 403 / code 1006，且不进入业务链")
    void passwordMustChangeBlocksWrite() throws Exception {
        WghUser user = activeUser();
        user.setPasswordMustChange(1);
        when(userMapper.selectById(1L)).thenReturn(user);
        when(userRoleMapper.selectRoleCodesByUserId(1L)).thenReturn(List.of("admin"));

        MockHttpServletResponse response = doFilter("POST", "/api/kb", token("admin", 1L, 1));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString())
                .contains("\"code\":" + ResultCode.PASSWORD_CHANGE_REQUIRED.getCode());
        assertThat(capturedLoginUser).isNull();
    }

    @Test
    @DisplayName("首登未改密：GET 读请求放行")
    void passwordMustChangeAllowsRead() throws Exception {
        WghUser user = activeUser();
        user.setPasswordMustChange(1);
        when(userMapper.selectById(1L)).thenReturn(user);
        when(userRoleMapper.selectRoleCodesByUserId(1L)).thenReturn(List.of("admin"));

        MockHttpServletResponse response = doFilter("GET", "/api/kb", token("admin", 1L, 1));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(capturedLoginUser).isNotNull();
    }

    @Test
    @DisplayName("首登未改密：改密接口自身放行")
    void passwordChangeEndpointAllowed() throws Exception {
        WghUser user = activeUser();
        user.setPasswordMustChange(1);
        when(userMapper.selectById(1L)).thenReturn(user);
        when(userRoleMapper.selectRoleCodesByUserId(1L)).thenReturn(List.of("admin"));

        MockHttpServletResponse response = doFilter("PUT", "/api/auth/password", token("admin", 1L, 1));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(capturedLoginUser).isNotNull();
    }

    // ===== 白名单 / 缺失凭证 =====

    @Test
    @DisplayName("白名单路径（登录接口）无凭证也放行")
    void whitelistedLoginPathAllowed() throws Exception {
        MockHttpServletResponse response = doFilter("POST", "/api/auth/login", null);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(capturedLoginUser).isNull();
    }

    @Test
    @DisplayName("缺少 Bearer 头 → 401 / code 1001")
    void missingBearerRejected() throws Exception {
        MockHttpServletResponse response = doFilter("GET", "/api/kb", null);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"code\":" + ResultCode.UNAUTHORIZED.getCode());
    }

    @Test
    @DisplayName("账号无数据库角色 → 回退 viewer，权限为空集合而不是 null")
    void noDbRolesFallsBackToViewer() throws Exception {
        WghUser user = activeUser();
        when(userMapper.selectById(1L)).thenReturn(user);
        when(userRoleMapper.selectRoleCodesByUserId(1L)).thenReturn(List.of());
        when(rolePermissionMapper.selectPermCodesByUserId(1L)).thenReturn(List.of());

        MockHttpServletResponse response = doFilter("GET", "/api/kb", token("admin", 1L, 1));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(capturedLoginUser.getRoles()).containsExactly(LoginUser.DEFAULT_ROLE);
        assertThat(capturedLoginUser.getPermissions()).isEmpty();
    }

    // ===== 辅助 =====

    private WghUser activeUser() {
        WghUser user = new WghUser();
        user.setId(1L);
        user.setUsername("admin");
        user.setStatus(1);
        user.setPasswordVersion(1);
        user.setPasswordMustChange(0);
        return user;
    }

    private String token(String subject, Long userId, Integer pv) {
        return token(subject, userId, pv, "admin");
    }

    private String token(String subject, Long userId, Integer pv, String... roles) {
        return localTokenService.issue(subject, userId, Set.of(roles), pv).token();
    }

    private MockHttpServletResponse doFilter(String method, String uri, String rawToken) throws Exception {
        LoginUser.clear();
        capturedLoginUser = null;

        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        if (rawToken != null) {
            request.addHeader("Authorization", "Bearer " + rawToken);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<LoginUser> ref = new AtomicReference<>();
        filter.doFilter(request, response, (req, res) -> ref.set(LoginUser.get()));
        capturedLoginUser = ref.get();
        return response;
    }
}
