package com.wgh.rag.quota;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.auth.config.SecurityConfig;
import com.wgh.rag.auth.security.LocalAuthProperties;
import com.wgh.rag.auth.security.LocalTokenService;
import com.wgh.rag.auth.security.OidcAuthenticationFilter;
import com.wgh.rag.auth.security.OidcProperties;
import com.wgh.rag.auth.security.OidcTokenVerifier;
import com.wgh.rag.auth.security.PasswordPolicy;
import com.wgh.rag.auth.security.PasswordProperties;
import com.wgh.rag.auth.security.TokenVerificationException;
import com.wgh.rag.auth.service.AccountAuditService;
import com.wgh.rag.auth.service.LocalLoginService;
import com.wgh.rag.auth.service.LocalPasswordService;
import com.wgh.rag.common.service.AuditLogService;
import com.wgh.rag.common.config.MetaObjectHandlerImpl;
import com.wgh.rag.common.exception.GlobalExceptionHandler;
import com.wgh.rag.controller.AdminUserController;
import com.wgh.rag.controller.AuthController;
import com.wgh.rag.controller.QuotaAdminController;
import com.wgh.rag.controller.QuotaUserController;
import com.wgh.rag.quota.properties.QuotaProperties;
import com.wgh.rag.quota.service.DefaultQuotaUserResolver;
import com.wgh.rag.quota.service.QuotaAdminGuard;
import com.wgh.rag.quota.service.QuotaService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 本地登录 + 体验账号全栈集成测试（REST → H2）。
 * <p>
 * 覆盖：本地管理员登录、密码错误、非管理员本地账号拒绝、体验账号创建/登录/权限边界/
 * 禁用/到期/禁止创建 admin、外部 token 仍可用、伪造本地凭证被拒。
 */
@SpringBootTest(classes = LocalAuthApiTest.TestApp.class)
@AutoConfigureMockMvc
@Transactional
@Sql("/schema-auth-local.sql")
@DisplayName("本地登录与体验账号集成测试")
class LocalAuthApiTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @MapperScan({"com.wgh.rag.quota.mapper", "com.wgh.rag.common.mapper"})
    @EnableConfigurationProperties(QuotaProperties.class)
    @Import({
            SecurityConfig.class,
            OidcAuthenticationFilter.class,
            OidcProperties.class,
            LocalAuthProperties.class,
            LocalTokenService.class,
            PasswordProperties.class,
            PasswordPolicy.class,
            LocalLoginService.class,
            LocalPasswordService.class,
            AccountAuditService.class,
            AuditLogService.class,
            MetaObjectHandlerImpl.class,
            GlobalExceptionHandler.class,
            QuotaService.class,
            DefaultQuotaUserResolver.class,
            QuotaAdminGuard.class,
            AuthController.class,
            AdminUserController.class,
            QuotaAdminController.class,
            QuotaUserController.class
    })
    static class TestApp {
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private OidcTokenVerifier tokenVerifier;

    private final ObjectMapper om = new ObjectMapper();

    @BeforeEach
    void seedLocalAccounts() {
        // 清空共享 schema 的种子用户，改用显式高位 id 重建，避免与新建体验账号的序列 id 冲突
        jdbcTemplate.update("DELETE FROM wgh_user_role");
        jdbcTemplate.update("DELETE FROM wgh_user");
        jdbcTemplate.update("INSERT INTO wgh_user(id, username, password_hash, source, trial, status) "
                + "VALUES(5001, 'admin', ?, 'local', 0, 1)", passwordEncoder.encode("admin"));
        jdbcTemplate.update("INSERT INTO wgh_user(id, username, password_hash, source, trial, status) "
                + "VALUES(5002, 'editor', ?, 'local', 0, 1)", passwordEncoder.encode("editor-pass"));
        jdbcTemplate.update("INSERT INTO wgh_user(id, username, password_hash, source, trial, status) "
                + "VALUES(5003, 'oidcuser', ?, 'oidc', 0, 1)", passwordEncoder.encode("oidc-pass"));
        jdbcTemplate.update("INSERT INTO wgh_user_role(user_id, role_id) VALUES(5001, 1), (5002, 2), (5003, 3)");
    }

    // ==================== 本地管理员登录 ====================

    @Test
    @DisplayName("admin/admin 登录成功，可访问管理端用户列表")
    void adminLoginAndListUsers() throws Exception {
        JsonNode login = login("admin", "admin");
        assertThat(login.path("code").asInt()).isEqualTo(200);
        assertThat(login.path("data").path("tokenType").asText()).isEqualTo("local");
        assertThat(login.path("data").path("user").path("roles").toString()).contains("admin");
        String token = login.path("data").path("token").asText();

        MvcResult list = mockMvc.perform(get("/api/admin/users")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = om.readTree(list.getResponse().getContentAsString());
        assertThat(body.path("code").asInt()).isEqualTo(200);
        assertThat(body.path("data").path("total").asInt()).isGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("admin 密码错误：业务码 1005")
    void adminWrongPasswordRejected() throws Exception {
        JsonNode login = login("admin", "wrong");
        assertThat(login.path("code").asInt()).isEqualTo(1005);
        assertThat(login.path("message").asText()).contains("用户名或密码错误");
    }

    @Test
    @DisplayName("本地非管理员非体验账号（editor）不能密码登录")
    void localEditorRejected() throws Exception {
        JsonNode login = login("editor", "editor-pass");
        assertThat(login.path("code").asInt()).isEqualTo(1005);
        assertThat(login.path("message").asText()).contains("仅本地管理员或体验账号");
    }

    @Test
    @DisplayName("外部 OIDC 来源账号不能密码登录")
    void oidcSourceRejected() throws Exception {
        JsonNode login = login("oidcuser", "oidc-pass");
        assertThat(login.path("code").asInt()).isEqualTo(1005);
        assertThat(login.path("message").asText()).contains("仅支持外部 OIDC 登录");
    }

    // ==================== 体验账号 ====================

    @Test
    @DisplayName("创建体验账号 → 登录成功 → 角色允许接口 200、管理接口 1004")
    void trialAccountLifecycleAndPermissions() throws Exception {
        String adminToken = login("admin", "admin").path("data").path("token").asText();

        JsonNode created = postJson("/api/admin/users", adminToken,
                "{\"username\":\"trial01\",\"password\":\"trial-pass\",\"role\":\"viewer\","
                        + "\"enabled\":true,\"remark\":\"验收\",\"chatLimit\":5,\"tokenLimit\":1000}");
        assertThat(created.path("code").asInt()).isEqualTo(200);
        assertThat(created.path("data").path("trial").asBoolean()).isTrue();

        JsonNode trialLogin = login("trial01", "trial-pass");
        assertThat(trialLogin.path("code").asInt()).isEqualTo(200);
        String trialToken = trialLogin.path("data").path("token").asText();

        // 允许：本人配额接口
        JsonNode my = getJson("/api/quota/my", trialToken);
        assertThat(my.path("code").asInt()).isEqualTo(200);

        // 不允许：管理员用户管理与配额管理 → 1004
        assertThat(getJson("/api/admin/users", trialToken).path("code").asInt()).isEqualTo(1004);
        assertThat(getJson("/api/admin/quota/users", trialToken).path("code").asInt()).isEqualTo(1004);
    }

    @Test
    @DisplayName("禁用体验账号后登录被拒")
    void disabledTrialAccountRejected() throws Exception {
        String adminToken = login("admin", "admin").path("data").path("token").asText();
        postJson("/api/admin/users", adminToken,
                "{\"username\":\"trial02\",\"password\":\"trial-pass\",\"role\":\"viewer\"}");
        putJson("/api/admin/users/trial02", adminToken, "{\"enabled\":false}");

        JsonNode login = login("trial02", "trial-pass");
        assertThat(login.path("code").asInt()).isEqualTo(1005);
        assertThat(login.path("message").asText()).contains("账号已禁用");
    }

    @Test
    @DisplayName("到期体验账号登录被拒")
    void expiredTrialAccountRejected() throws Exception {
        String adminToken = login("admin", "admin").path("data").path("token").asText();
        postJson("/api/admin/users", adminToken,
                "{\"username\":\"trial03\",\"password\":\"trial-pass\",\"role\":\"viewer\","
                        + "\"expireAt\":\"2020-01-01T00:00:00\"}");

        JsonNode login = login("trial03", "trial-pass");
        assertThat(login.path("code").asInt()).isEqualTo(1005);
        assertThat(login.path("message").asText()).contains("账号已过期");
    }

    @Test
    @DisplayName("通过接口创建 admin 角色账号被拒（3003）")
    void createAdminRoleRejected() throws Exception {
        String adminToken = login("admin", "admin").path("data").path("token").asText();
        JsonNode created = postJson("/api/admin/users", adminToken,
                "{\"username\":\"evil\",\"password\":\"evil-pass\",\"role\":\"admin\"}");
        assertThat(created.path("code").asInt()).isEqualTo(3003);
        assertThat(created.path("message").asText()).contains("管理员");
    }

    @Test
    @DisplayName("体验账号不能被提升为 admin 角色")
    void promoteTrialToAdminRejected() throws Exception {
        String adminToken = login("admin", "admin").path("data").path("token").asText();
        postJson("/api/admin/users", adminToken,
                "{\"username\":\"trial04\",\"password\":\"trial-pass\",\"role\":\"viewer\"}");

        JsonNode updated = putJson("/api/admin/users/trial04", adminToken, "{\"role\":\"admin\"}");
        assertThat(updated.path("code").asInt()).isEqualTo(3003);
    }

    // ==================== 首登强制改密 ====================

    @Test
    @DisplayName("首登强制改密：登录返回标记，写接口被拒 403/1006，读取接口仍可用")
    void firstLoginMustChangePassword() throws Exception {
        jdbcTemplate.update("UPDATE wgh_user SET password_must_change=1 WHERE username='admin'");

        JsonNode login = login("admin", "admin");
        assertThat(login.path("data").path("passwordMustChange").asBoolean()).isTrue();

        String token = login.path("data").path("token").asText();
        MvcResult blocked = mockMvc.perform(post("/api/admin/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andReturn();
        JsonNode blockedBody = om.readTree(blocked.getResponse().getContentAsString());
        assertThat(blockedBody.path("code").asInt()).isEqualTo(1006);

        // 只读接口不受影响
        assertThat(getJson("/api/auth/me", token).path("code").asInt()).isEqualTo(200);
    }

    @Test
    @DisplayName("改密成功后清除强制改密标记并可使用写接口")
    void changePasswordClearsMustChangeFlag() throws Exception {
        jdbcTemplate.update("UPDATE wgh_user SET password_must_change=1 WHERE username='admin'");
        String token = login("admin", "admin").path("data").path("token").asText();

        JsonNode changed = postJson("/api/auth/password", token,
                "{\"oldPassword\":\"admin\",\"newPassword\":\"newAdmin123\"}");
        assertThat(changed.path("code").asInt()).isEqualTo(200);

        JsonNode me = getJson("/api/auth/me", token);
        assertThat(me.path("data").path("passwordMustChange").asBoolean()).isFalse();
        assertThat(getJson("/api/admin/users", token).path("code").asInt()).isEqualTo(200);
    }

    @Test
    @DisplayName("改密与重置口令的审计记录不含明文密码")
    void passwordAuditIsMasked() throws Exception {
        String adminToken = login("admin", "admin").path("data").path("token").asText();
        postJson("/api/admin/users", adminToken,
                "{\"username\":\"trial06\",\"password\":\"trial-pass\",\"role\":\"viewer\"}");

        // 本人改密
        postJson("/api/auth/password", adminToken,
                "{\"oldPassword\":\"admin\",\"newPassword\":\"newAdmin123\"}");
        // 管理员重置体验账号口令
        JsonNode reset = putJson("/api/admin/users/trial06/password", adminToken,
                "{\"newPassword\":\"resetPass123\"}");
        assertThat(reset.path("code").asInt()).isEqualTo(200);

        String details = jdbcTemplate.queryForObject(
                "SELECT COALESCE(STRING_AGG(detail, ' | '), '') FROM wgh_audit_log", String.class);
        assertThat(details).contains("target=admin").contains("result=SUCCESS");
        assertThat(details).contains("target=trial06");
        // 审计详情不得出现任何口令明文
        assertThat(details).doesNotContain("newAdmin123")
                .doesNotContain("resetPass123").doesNotContain("trial-pass")
                .doesNotContain("oldPassword");
    }

    @Test
    @DisplayName("管理员重置体验账号口令：旧口令失效、新口令可登录且需先改密")
    void resetTrialPasswordFlow() throws Exception {
        String adminToken = login("admin", "admin").path("data").path("token").asText();
        postJson("/api/admin/users", adminToken,
                "{\"username\":\"trial07\",\"password\":\"trial-pass\",\"role\":\"viewer\"}");
        assertThat(login("trial07", "trial-pass").path("code").asInt()).isEqualTo(200);

        JsonNode reset = putJson("/api/admin/users/trial07/password", adminToken,
                "{\"newPassword\":\"resetPass123\"}");
        assertThat(reset.path("code").asInt()).isEqualTo(200);

        assertThat(login("trial07", "trial-pass").path("code").asInt()).isEqualTo(1005);

        JsonNode relogin = login("trial07", "resetPass123");
        assertThat(relogin.path("code").asInt()).isEqualTo(200);
        assertThat(relogin.path("data").path("passwordMustChange").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("非体验账号（editor）不允许管理员重置口令")
    void resetNonTrialRejected() throws Exception {
        String adminToken = login("admin", "admin").path("data").path("token").asText();
        JsonNode reset = putJson("/api/admin/users/editor/password", adminToken,
                "{\"newPassword\":\"resetPass123\"}");
        assertThat(reset.path("code").asInt()).isEqualTo(3003);
    }

    @Test
    @DisplayName("密码强度不足被拒（2001）")
    void weakNewPasswordRejected() throws Exception {
        String adminToken = login("admin", "admin").path("data").path("token").asText();
        JsonNode changed = postJson("/api/auth/password", adminToken,
                "{\"oldPassword\":\"admin\",\"newPassword\":\"short\"}");
        assertThat(changed.path("code").asInt()).isEqualTo(2001);
        assertThat(changed.path("message").asText()).contains("至少 8 位");
    }

    // ==================== 双认证隔离 ====================

    @Test
    @DisplayName("外部 OIDC admin token 仍可访问 /api/auth/me")
    void externalTokenStillWorks() throws Exception {
        Claims claims = Jwts.claims()
                .subject("external-admin")
                .add("userId", 100L)
                .add("roles", Arrays.asList("admin"))
                .build();
        when(tokenVerifier.verify(anyString())).thenReturn(claims);

        JsonNode me = getJson("/api/auth/me", "external-admin-token");
        assertThat(me.path("code").asInt()).isEqualTo(200);
    }

    @Test
    @DisplayName("外部 token 验签失败（篡改签名）→ HTTP 401 + 1002")
    void tamperedExternalTokenRejected() throws Exception {
        when(tokenVerifier.verify(anyString()))
                .thenThrow(new TokenVerificationException("签名校验失败"));

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer tampered-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("用错误密钥伪造的本地凭证被拒（HTTP 401）")
    void forgedLocalTokenRejected() throws Exception {
        LocalAuthProperties other = new LocalAuthProperties();
        other.setJwtSecret("attacker-secret-0123456789abcdef0123456789abcdef");
        String forged = new LocalTokenService(other)
                .issue("admin", 1L, java.util.Set.of("admin")).token();

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + forged))
                .andExpect(status().isUnauthorized());
    }

    // ==================== helpers ====================

    private JsonNode login(String username, String password) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return om.readTree(res.getResponse().getContentAsString());
    }

    private JsonNode postJson(String url, String token, String body) throws Exception {
        MvcResult res = mockMvc.perform(post(url)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        return om.readTree(res.getResponse().getContentAsString());
    }

    private JsonNode putJson(String url, String token, String body) throws Exception {
        MvcResult res = mockMvc.perform(put(url)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        return om.readTree(res.getResponse().getContentAsString());
    }

    private JsonNode getJson(String url, String token) throws Exception {
        MvcResult res = mockMvc.perform(get(url)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return om.readTree(res.getResponse().getContentAsString());
    }
}
