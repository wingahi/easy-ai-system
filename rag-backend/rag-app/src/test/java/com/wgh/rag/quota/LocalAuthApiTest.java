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
import com.wgh.rag.auth.security.PermissionAspect;
import com.wgh.rag.auth.security.TokenVerificationException;
import com.wgh.rag.auth.service.AccountAuditService;
import com.wgh.rag.auth.service.LocalLoginService;
import com.wgh.rag.auth.service.LocalPasswordService;
import com.wgh.rag.common.metrics.RagMetrics;
import com.wgh.rag.common.result.Result;
import com.wgh.rag.common.security.RequiresPermission;
import com.wgh.rag.log.service.AuditLogService;
import com.wgh.rag.common.config.MetaObjectHandlerImpl;
import com.wgh.rag.common.exception.GlobalExceptionHandler;
import com.wgh.rag.controller.AdminRoleController;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
    @MapperScan({"com.wgh.rag.quota.mapper", "com.wgh.rag.common.mapper", "com.wgh.rag.log.mapper"})
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
            RagMetrics.class,
            QuotaService.class,
            DefaultQuotaUserResolver.class,
            QuotaAdminGuard.class,
            PermissionAspect.class,
            AuthController.class,
            AdminUserController.class,
            AdminRoleController.class,
            QuotaAdminController.class,
            QuotaUserController.class,
            TestPermissionController.class
    })
    static class TestApp {
    }

    /** 测试专用：验证"权限变更立即生效"的最小受保护接口 */
    @RestController
    static class TestPermissionController {

        @GetMapping("/api/test/permission")
        @RequiresPermission("kb:read")
        public Result<String> check() {
            return Result.success("ok");
        }
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

        // password_version 自增：改密前的旧凭证立即失效（401 + 1007）
        MvcResult invalid = mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertThat(om.readTree(invalid.getResponse().getContentAsString()).path("code").asInt())
                .isEqualTo(1007);

        // 新凭证正常，且强制改密标记已清除
        JsonNode relogin = login("admin", "newAdmin123");
        assertThat(relogin.path("code").asInt()).isEqualTo(200);
        assertThat(relogin.path("data").path("passwordMustChange").asBoolean()).isFalse();
        String newToken = relogin.path("data").path("token").asText();
        assertThat(getJson("/api/admin/users", newToken).path("code").asInt()).isEqualTo(200);
    }

    @Test
    @DisplayName("改密与重置口令的审计记录不含明文密码")
    void passwordAuditIsMasked() throws Exception {
        String adminToken = login("admin", "admin").path("data").path("token").asText();
        postJson("/api/admin/users", adminToken,
                "{\"username\":\"trial06\",\"password\":\"trial-pass\",\"role\":\"viewer\"}");

        // 本人改密（改密后旧凭证失效，需重新登录）
        postJson("/api/auth/password", adminToken,
                "{\"oldPassword\":\"admin\",\"newPassword\":\"newAdmin123\"}");
        String freshAdminToken = login("admin", "newAdmin123").path("data").path("token").asText();
        // 管理员重置体验账号口令
        JsonNode reset = putJson("/api/admin/users/trial06/password", freshAdminToken,
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
        JsonNode firstLogin = login("trial07", "trial-pass");
        assertThat(firstLogin.path("code").asInt()).isEqualTo(200);
        String oldTrialToken = firstLogin.path("data").path("token").asText();

        JsonNode reset = putJson("/api/admin/users/trial07/password", adminToken,
                "{\"newPassword\":\"resetPass123\"}");
        assertThat(reset.path("code").asInt()).isEqualTo(200);

        // 重置口令后旧凭证立即失效（password_version 自增）
        MvcResult invalid = mockMvc.perform(get("/api/quota/my")
                        .header("Authorization", "Bearer " + oldTrialToken))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertThat(om.readTree(invalid.getResponse().getContentAsString()).path("code").asInt())
                .isEqualTo(1007);

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
                .issue("admin", 1L, java.util.Set.of("admin"), 1).token();

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + forged))
                .andExpect(status().isUnauthorized());
    }

    // ==================== 生命周期审计 / 事务 ====================

    @Test
    @DisplayName("重复创建同名体验账号返回 409（唯一键冲突不落 500）")
    void duplicateCreateReturns409() throws Exception {
        String adminToken = login("admin", "admin").path("data").path("token").asText();
        String body = "{\"username\":\"trial09\",\"password\":\"trial-pass\",\"role\":\"viewer\"}";
        postJson("/api/admin/users", adminToken, body);

        mockMvc.perform(post("/api/admin/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("账号生命周期（创建/改角色/禁用/登录失败）写入审计")
    void accountLifecycleAudited() throws Exception {
        String adminToken = login("admin", "admin").path("data").path("token").asText();
        postJson("/api/admin/users", adminToken,
                "{\"username\":\"trial10\",\"password\":\"trial-pass\",\"role\":\"viewer\"}");
        putJson("/api/admin/users/trial10", adminToken, "{\"role\":\"editor\"}");
        putJson("/api/admin/users/trial10", adminToken, "{\"enabled\":false}");
        login("trial10", "trial-pass");

        String actions = jdbcTemplate.queryForObject(
                "SELECT COALESCE(STRING_AGG(action, ','), '') FROM wgh_audit_log", String.class);
        assertThat(actions).contains("ACCOUNT_CREATE")
                .contains("ACCOUNT_ROLE_CHANGE")
                .contains("ACCOUNT_DISABLE")
                .contains("ACCOUNT_LOGIN_FAIL");

        String details = jdbcTemplate.queryForObject(
                "SELECT COALESCE(STRING_AGG(detail, ' | '), '') FROM wgh_audit_log", String.class);
        assertThat(details).contains("target=trial10");
        assertThat(details).doesNotContain("trial-pass");
    }

    @Test
    @DisplayName("登录成功也写入审计（目标与操作者为 admin 本人）")
    void loginSuccessAudited() throws Exception {
        login("admin", "admin");
        String actions = jdbcTemplate.queryForObject(
                "SELECT COALESCE(STRING_AGG(action, ','), '') FROM wgh_audit_log", String.class);
        assertThat(actions).contains("ACCOUNT_LOGIN_SUCCESS");
    }

    // ==================== 未映射路径 ====================

    @Test
    @DisplayName("未映射 /api 路径：HTTP 404 + 业务码 3001（不再落兜底 500）")
    void unmappedApiPathReturns404() throws Exception {
        String adminToken = login("admin", "admin").path("data").path("token").asText();

        MvcResult res = mockMvc.perform(get("/api/not-exists")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound())
                .andReturn();
        JsonNode body = om.readTree(res.getResponse().getContentAsString());
        assertThat(body.path("code").asInt()).isEqualTo(3001);
        assertThat(body.path("message").asText()).contains("接口不存在");
    }

    @Test
    @DisplayName("/api/admin/quota 根路径未映射 → 404；/api/admin/quota/users 仍 200")
    void quotaRootPathReturns404() throws Exception {
        String adminToken = login("admin", "admin").path("data").path("token").asText();

        mockMvc.perform(get("/api/admin/quota")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());

        assertThat(getJson("/api/admin/quota/users", adminToken).path("code").asInt()).isEqualTo(200);
    }

    // ==================== 角色权限管理 ====================

    @Test
    @DisplayName("角色 CRUD：创建 → 改名 → 列表含内置标记/用户数/权限数")
    void roleCrud() throws Exception {
        String adminToken = login("admin", "admin").path("data").path("token").asText();

        JsonNode created = postJson("/api/admin/roles", adminToken,
                "{\"roleCode\":\"auditor\",\"roleName\":\"审计员\",\"description\":\"只读审计\"}");
        assertThat(created.path("code").asInt()).isEqualTo(200);
        assertThat(created.path("data").path("builtIn").asBoolean()).isFalse();
        assertThat(created.path("data").path("userCount").asInt()).isZero();
        assertThat(created.path("data").path("permissionCount").asInt()).isZero();

        JsonNode updated = putJson("/api/admin/roles/auditor", adminToken,
                "{\"roleName\":\"审计角色\",\"description\":\"查看审计日志\"}");
        assertThat(updated.path("data").path("roleName").asText()).isEqualTo("审计角色");

        JsonNode roles = getJson("/api/admin/roles", adminToken).path("data");
        assertThat(findRole(roles, "auditor").path("description").asText()).isEqualTo("查看审计日志");
        assertThat(findRole(roles, "admin").path("builtIn").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("role_code 冲突返回 HTTP 409")
    void duplicateRoleCodeReturns409() throws Exception {
        String adminToken = login("admin", "admin").path("data").path("token").asText();
        String body = "{\"roleCode\":\"dup_role\",\"roleName\":\"重复角色\"}";
        postJson("/api/admin/roles", adminToken, body);

        mockMvc.perform(post("/api/admin/roles")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("删除仍有用户绑定的角色：HTTP 409 + 3003，不静默解绑")
    void deleteBoundRoleRejected() throws Exception {
        String adminToken = login("admin", "admin").path("data").path("token").asText();
        postJson("/api/admin/roles", adminToken,
                "{\"roleCode\":\"bound_role\",\"roleName\":\"绑定角色\"}");
        postJson("/api/admin/users", adminToken,
                "{\"username\":\"trial-bound\",\"password\":\"trial-pass\",\"role\":\"bound_role\"}");

        MvcResult res = mockMvc.perform(delete("/api/admin/roles/bound_role")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isConflict())
                .andReturn();
        JsonNode body = om.readTree(res.getResponse().getContentAsString());
        assertThat(body.path("code").asInt()).isEqualTo(3003);
        assertThat(body.path("message").asText()).contains("绑定");
    }

    @Test
    @DisplayName("内置 admin：不可改名、不可删除、不可削减权限；提交全量权限幂等放行")
    void builtInAdminRoleProtected() throws Exception {
        String adminToken = login("admin", "admin").path("data").path("token").asText();

        assertThat(putJson("/api/admin/roles/admin", adminToken,
                "{\"roleName\":\"超级管理员\"}").path("code").asInt()).isEqualTo(3003);
        assertThat(deleteJson("/api/admin/roles/admin", adminToken).path("code").asInt()).isEqualTo(3003);
        assertThat(putJson("/api/admin/roles/admin/permissions", adminToken,
                "{\"permCodes\":[\"kb:read\"]}").path("code").asInt()).isEqualTo(3003);

        // 提交全量权限：等价于无变更，幂等放行
        JsonNode allPermissions = getJson("/api/admin/permissions", adminToken).path("data");
        String fullSet = buildPermCodesJson(allPermissions);
        assertThat(putJson("/api/admin/roles/admin/permissions", adminToken,
                "{\"permCodes\":" + fullSet + "}").path("code").asInt()).isEqualTo(200);
    }

    @Test
    @DisplayName("角色接口鉴权：未登录 401，体验账号访问返回 1004")
    void roleEndpointsRejectUnauthorized() throws Exception {
        mockMvc.perform(get("/api/admin/roles"))
                .andExpect(status().isUnauthorized());

        postJson("/api/admin/users", login("admin", "admin").path("data").path("token").asText(),
                "{\"username\":\"trial-role-guard\",\"password\":\"trial-pass\",\"role\":\"viewer\"}");
        String trialToken = login("trial-role-guard", "trial-pass").path("data").path("token").asText();
        assertThat(getJson("/api/admin/roles", trialToken).path("code").asInt()).isEqualTo(1004);
    }

    @Test
    @DisplayName("未知权限码直接拒绝（2001），不写入脏数据")
    void unknownPermissionCodeRejected() throws Exception {
        String adminToken = login("admin", "admin").path("data").path("token").asText();
        postJson("/api/admin/roles", adminToken,
                "{\"roleCode\":\"unknown_perm_role\",\"roleName\":\"测试角色\"}");

        JsonNode body = putJson("/api/admin/roles/unknown_perm_role/permissions", adminToken,
                "{\"permCodes\":[\"kb:read\",\"bogus:perm\"]}");
        assertThat(body.path("code").asInt()).isEqualTo(2001);
        assertThat(body.path("message").asText()).contains("未知权限码");
    }

    @Test
    @DisplayName("权限变更对已登录用户立即生效：同一 token 下一个请求 200 → 1004")
    void permissionChangeTakesEffectImmediately() throws Exception {
        String adminToken = login("admin", "admin").path("data").path("token").asText();
        postJson("/api/admin/roles", adminToken,
                "{\"roleCode\":\"kb_reader\",\"roleName\":\"知识库只读\"}");
        putJson("/api/admin/roles/kb_reader/permissions", adminToken,
                "{\"permCodes\":[\"kb:read\"]}");
        postJson("/api/admin/users", adminToken,
                "{\"username\":\"trial-kb\",\"password\":\"trial-pass\",\"role\":\"kb_reader\"}");

        String trialToken = login("trial-kb", "trial-pass").path("data").path("token").asText();
        assertThat(getJson("/api/test/permission", trialToken).path("code").asInt()).isEqualTo(200);

        // 削减权限：已登录 token 无需重新登录，下一次请求即被拒
        putJson("/api/admin/roles/kb_reader/permissions", adminToken, "{\"permCodes\":[]}");
        assertThat(getJson("/api/test/permission", trialToken).path("code").asInt()).isEqualTo(1004);
    }

    // ==================== helpers ====================

    private JsonNode deleteJson(String url, String token) throws Exception {
        MvcResult res = mockMvc.perform(delete(url)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return om.readTree(res.getResponse().getContentAsString());
    }

    private JsonNode findRole(JsonNode roles, String roleCode) {
        for (JsonNode role : roles) {
            if (roleCode.equals(role.path("roleCode").asText())) {
                return role;
            }
        }
        return null;
    }

    private String buildPermCodesJson(JsonNode groupedPermissions) {
        StringBuilder sb = new StringBuilder("[");
        for (JsonNode group : groupedPermissions) {
            for (JsonNode permission : group.path("permissions")) {
                if (sb.length() > 1) {
                    sb.append(',');
                }
                sb.append('"').append(permission.path("permCode").asText()).append('"');
            }
        }
        return sb.append(']').toString();
    }

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
