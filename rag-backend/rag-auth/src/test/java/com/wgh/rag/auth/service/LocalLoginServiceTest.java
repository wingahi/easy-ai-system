package com.wgh.rag.auth.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.wgh.rag.auth.dto.LocalLoginResult;
import com.wgh.rag.auth.security.LocalAuthProperties;
import com.wgh.rag.auth.security.LocalTokenService;
import com.wgh.rag.common.entity.WghUser;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.mapper.RolePermissionMapper;
import com.wgh.rag.common.mapper.UserMapper;
import com.wgh.rag.common.mapper.UserRoleMapper;
import com.wgh.rag.common.result.ResultCode;
import io.jsonwebtoken.Claims;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 本地账号密码登录规则测试：仅本地管理员/体验账号可通过密码登录，外部 OIDC 账号被拒。
 */
@DisplayName("本地账号密码登录")
class LocalLoginServiceTest {

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private UserMapper userMapper;
    private UserRoleMapper userRoleMapper;
    private RolePermissionMapper rolePermissionMapper;
    private LocalTokenService localTokenService;
    private LocalLoginService localLoginService;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), WghUser.class);
    }

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        userRoleMapper = mock(UserRoleMapper.class);
        rolePermissionMapper = mock(RolePermissionMapper.class);

        LocalAuthProperties properties = new LocalAuthProperties();
        properties.setJwtSecret("unit-test-local-secret-0123456789abcdef0123456789");
        localTokenService = new LocalTokenService(properties);
        AccountAuditService accountAuditService = new AccountAuditService(
                new com.wgh.rag.log.service.AuditLogService(
                        mock(com.wgh.rag.log.mapper.AuditLogMapper.class)));
        localLoginService = new LocalLoginService(userMapper, userRoleMapper,
                rolePermissionMapper, passwordEncoder, localTokenService, properties, accountAuditService);
    }

    private WghUser user(String username, String password, String source, Integer trial,
                         Integer status, LocalDateTime expireAt) {
        WghUser user = new WghUser();
        user.setId(1L);
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setSource(source);
        user.setTrial(trial);
        user.setStatus(status);
        user.setExpireAt(expireAt);
        when(userMapper.selectOne(any())).thenReturn(user);
        return user;
    }

    @Test
    @DisplayName("本地管理员密码正确：签发会话凭证，角色/权限来自数据库")
    void adminLoginSuccess() {
        user("admin", "admin", "local", 0, 1, null);
        when(userRoleMapper.selectRoleCodesByUserId(1L)).thenReturn(List.of("admin"));
        when(rolePermissionMapper.selectPermCodesByUserId(1L))
                .thenReturn(List.of("kb:read", "kb:write"));

        LocalLoginResult result = localLoginService.login("admin", "admin");

        assertThat(result.getTokenType()).isEqualTo("local");
        assertThat(result.getExpiresIn()).isPositive();
        assertThat(result.getUser().getRoles()).containsExactly("admin");
        assertThat(result.getUser().getPermissions()).containsExactly("kb:read", "kb:write");

        Claims claims = localTokenService.verify(result.getToken());
        assertThat(claims.getSubject()).isEqualTo("admin");
        assertThat(claims.get("tokenType", String.class)).isEqualTo("local");
    }

    @Test
    @DisplayName("本地管理员密码错误：登录失败")
    void adminWrongPasswordRejected() {
        user("admin", "admin", "local", 0, 1, null);

        assertThatThrownBy(() -> localLoginService.login("admin", "wrong"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("用户名或密码错误");
    }

    @Test
    @DisplayName("本地非管理员非体验账号（viewer）：拒绝密码登录")
    void localNonTrialViewerRejected() {
        user("viewer", "viewer", "local", 0, 1, null);
        when(userRoleMapper.selectRoleCodesByUserId(1L)).thenReturn(List.of("viewer"));

        assertThatThrownBy(() -> localLoginService.login("viewer", "viewer"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("仅本地管理员或体验账号");
    }

    @Test
    @DisplayName("体验账号可密码登录，角色为 viewer")
    void trialAccountCanLogin() {
        user("trial01", "trial-pass", "local", 1, 1, null);
        when(userRoleMapper.selectRoleCodesByUserId(1L)).thenReturn(List.of("viewer"));
        when(rolePermissionMapper.selectPermCodesByUserId(1L)).thenReturn(List.of("kb:read"));

        LocalLoginResult result = localLoginService.login("trial01", "trial-pass");

        assertThat(result.getUser().getRoles()).containsExactly("viewer");
        assertThat(result.getUser().getPermissions()).containsExactly("kb:read");
    }

    @Test
    @DisplayName("外部 OIDC 账号不允许本地密码登录")
    void oidcSourceRejected() {
        user("alice", "alice-pass", "oidc", 0, 1, null);

        assertThatThrownBy(() -> localLoginService.login("alice", "alice-pass"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("仅支持外部 OIDC 登录");
    }

    @Test
    @DisplayName("外部 OIDC 账号即使密码哈希非空也拒绝密码登录")
    void oidcSourceWithHashRejected() {
        user("alice", "alice-pass", "oidc", 0, 1, null);

        assertThatThrownBy(() -> localLoginService.login("alice", "alice-pass"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ResultCode.LOGIN_FAILED.getCode());
    }

    @Test
    @DisplayName("外部 OIDC 账号密码哈希为空：拒绝密码登录（不落空指针/200）")
    void oidcSourceWithEmptyHashRejected() {
        WghUser emptyHashUser = new WghUser();
        emptyHashUser.setId(1L);
        emptyHashUser.setUsername("alice-empty");
        emptyHashUser.setPasswordHash("");
        emptyHashUser.setSource("oidc");
        emptyHashUser.setTrial(0);
        emptyHashUser.setStatus(1);
        when(userMapper.selectOne(any())).thenReturn(emptyHashUser);

        assertThatThrownBy(() -> localLoginService.login("alice-empty", "any"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("仅支持外部 OIDC 登录");
    }

    @Test
    @DisplayName("source 为空（非显式 local）：拒绝密码登录，避免 OIDC 账号误配密码后被放行")
    void nullSourceRejected() {
        user("legacy-user", "legacy-pass", null, 0, 1, null);

        assertThatThrownBy(() -> localLoginService.login("legacy-user", "legacy-pass"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("仅支持外部 OIDC 登录");
    }

    @Test
    @DisplayName("账号禁用：登录被拒")
    void disabledAccountRejected() {
        user("admin", "admin", "local", 0, 0, null);

        assertThatThrownBy(() -> localLoginService.login("admin", "admin"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("账号已禁用");
    }

    @Test
    @DisplayName("账号过期：登录被拒")
    void expiredAccountRejected() {
        user("trial02", "trial-pass", "local", 1, 1,
                LocalDateTime.now().minusDays(1));

        assertThatThrownBy(() -> localLoginService.login("trial02", "trial-pass"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("账号已过期");
    }

    @Test
    @DisplayName("本地登录关闭时直接拒绝")
    void localLoginDisabled() {
        LocalAuthProperties properties = new LocalAuthProperties();
        properties.setEnabled(false);
        LocalLoginService disabledService = new LocalLoginService(userMapper, userRoleMapper,
                rolePermissionMapper, passwordEncoder, localTokenService, properties,
                new AccountAuditService(new com.wgh.rag.log.service.AuditLogService(
                        mock(com.wgh.rag.log.mapper.AuditLogMapper.class))));

        assertThatThrownBy(() -> disabledService.login("admin", "admin"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ResultCode.LOGIN_FAILED.getCode());
    }
}
