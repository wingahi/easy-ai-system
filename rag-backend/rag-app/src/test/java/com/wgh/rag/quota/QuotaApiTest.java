package com.wgh.rag.quota;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.auth.config.SecurityConfig;
import com.wgh.rag.auth.security.JwtAuthenticationFilter;
import com.wgh.rag.auth.security.JwtUtils;
import com.wgh.rag.common.config.MetaObjectHandlerImpl;
import com.wgh.rag.common.exception.GlobalExceptionHandler;
import com.wgh.rag.controller.QuotaAdminController;
import com.wgh.rag.controller.QuotaUserController;
import com.wgh.rag.quota.properties.QuotaProperties;
import com.wgh.rag.quota.service.DefaultQuotaUserResolver;
import com.wgh.rag.quota.service.QuotaAdminGuard;
import com.wgh.rag.quota.service.QuotaService;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wgh.rag.common.exception.BusinessException;

/**
 * 配额全栈集成测试：REST → QuotaService → H2（PostgreSQL 模式）。
 * <p>
 * 使用切片上下文（仅装配配额链路 + 安全过滤器所需 bean），
 * 与 RagApplication 全量上下文隔离 —— 后者当前受未完成的 RBAC 改动影响
 * （rag-common 与 rag-auth 各有一个同名 RagAuthAutoConfig）无法加载。
 * <p>
 * 文件名故意不用 *IntegrationTest 后缀（surefire excludes 该模式）。
 * DB 操作靠 @Transactional 自动回滚。
 */
@SpringBootTest(classes = QuotaApiTest.TestApp.class)
@AutoConfigureMockMvc
@Transactional
@DisplayName("配额全栈集成测试（REST → 服务 → H2）")
class QuotaApiTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @MapperScan({"com.wgh.rag.quota.mapper", "com.wgh.rag.common.mapper"})
    @EnableConfigurationProperties(QuotaProperties.class)
    @Import({
            SecurityConfig.class,
            JwtAuthenticationFilter.class,
            JwtUtils.class,
            MetaObjectHandlerImpl.class,
            GlobalExceptionHandler.class,
            QuotaService.class,
            DefaultQuotaUserResolver.class,
            QuotaAdminGuard.class,
            QuotaAdminController.class,
            QuotaUserController.class
    })
    static class TestApp {
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private QuotaService quotaService;

    private final ObjectMapper om = new ObjectMapper();

    private String bearer(Long userId, String username) {
        return "Bearer " + jwtUtils.generateToken(userId, username);
    }

    /** 内置超级账号（H2 种子 wgh_user.id=1，角色 admin） */
    private String adminToken() {
        return bearer(1L, "admin");
    }

    private JsonNode putQuota(String username, String body) throws Exception {
        MvcResult res = mockMvc.perform(put("/api/admin/quota/users/" + username)
                        .header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        return om.readTree(res.getResponse().getContentAsString());
    }

    private JsonNode getMy(String token) throws Exception {
        MvcResult res = mockMvc.perform(get("/api/quota/my")
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andReturn();
        return om.readTree(res.getResponse().getContentAsString());
    }

    // ========== 管理端 CRUD ==========

    @Test
    @DisplayName("管理员设置用户配额后列表与用户侧均可见自定义配置")
    void adminSetsQuota_visibleInListAndMy() throws Exception {
        JsonNode put = putQuota("alice",
                "{\"period\":\"DAY\",\"chatLimit\":2,\"tokenLimit\":1000,\"enabled\":true,\"remark\":\"测试\"}");
        assertThat(put.path("code").asInt()).isEqualTo(200);
        assertThat(put.path("data").path("custom").asBoolean()).isTrue();
        assertThat(put.path("data").path("chatLimit").asInt()).isEqualTo(2);

        MvcResult list = mockMvc.perform(get("/api/admin/quota/users")
                        .header("Authorization", adminToken())
                        .param("username", "alice"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode rows = om.readTree(list.getResponse().getContentAsString())
                .path("data").path("records");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).path("username").asText()).isEqualTo("alice");
        assertThat(rows.get(0).path("custom").asBoolean()).isTrue();
        assertThat(rows.get(0).path("chatLimit").asInt()).isEqualTo(2);
        assertThat(rows.get(0).path("tokenLimit").asLong()).isEqualTo(1000L);
        assertThat(rows.get(0).path("remark").asText()).isEqualTo("测试");

        JsonNode my = getMy(bearer(99L, "alice"));
        assertThat(my.path("data").path("source").asText()).isEqualTo("CUSTOM");
        assertThat(my.path("data").path("chatLimit").asInt()).isEqualTo(2);
        assertThat(my.path("data").path("chatRemaining").asLong()).isEqualTo(2L);
    }

    @Test
    @DisplayName("非管理员访问配额管理接口被拒绝（1004）")
    void nonAdmin_forbidden() throws Exception {
        // H2 种子：editor(id=2) 角色 editor，非管理员
        MvcResult res = mockMvc.perform(put("/api/admin/quota/users/alice")
                        .header("Authorization", bearer(2L, "editor"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"chatLimit\":5}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json = om.readTree(res.getResponse().getContentAsString());
        assertThat(json.path("code").asInt()).isEqualTo(1004);
    }

    @Test
    @DisplayName("恢复默认：删除自定义配置后用户侧回退全局默认")
    void resetToDefault() throws Exception {
        putQuota("bob", "{\"period\":\"DAY\",\"chatLimit\":5,\"tokenLimit\":500,\"enabled\":true}");

        MvcResult del = mockMvc.perform(delete("/api/admin/quota/users/bob")
                        .header("Authorization", adminToken()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(om.readTree(del.getResponse().getContentAsString())
                .path("data").path("restoredDefault").asBoolean()).isTrue();

        JsonNode my = getMy(bearer(98L, "bob"));
        assertThat(my.path("data").path("source").asText()).isEqualTo("DEFAULT");
        assertThat(my.path("data").path("chatLimit").asInt()).isEqualTo(200);
        assertThat(my.path("data").path("tokenLimit").asLong()).isEqualTo(2_000_000L);
    }

    @Test
    @DisplayName("禁用自定义配置后回退全局默认，重新启用后再次生效")
    void disableFallsBack_enableRestores() throws Exception {
        putQuota("carol", "{\"period\":\"DAY\",\"chatLimit\":7,\"tokenLimit\":700,\"enabled\":true}");
        putQuota("carol", "{\"period\":\"DAY\",\"chatLimit\":7,\"tokenLimit\":700,\"enabled\":false}");

        JsonNode myDisabled = getMy(bearer(97L, "carol"));
        assertThat(myDisabled.path("data").path("source").asText()).isEqualTo("DEFAULT");

        putQuota("carol", "{\"period\":\"DAY\",\"chatLimit\":7,\"tokenLimit\":700,\"enabled\":true}");
        JsonNode myEnabled = getMy(bearer(97L, "carol"));
        assertThat(myEnabled.path("data").path("source").asText()).isEqualTo("CUSTOM");
        assertThat(myEnabled.path("data").path("chatLimit").asInt()).isEqualTo(7);
    }

    @Test
    @DisplayName("月周期配置：用户侧周期键为 yyyy-MM")
    void monthPeriodKeyVisible() throws Exception {
        putQuota("dave", "{\"period\":\"MONTH\",\"chatLimit\":30,\"tokenLimit\":3000,\"enabled\":true}");

        JsonNode my = getMy(bearer(96L, "dave"));
        assertThat(my.path("data").path("period").asText()).isEqualTo("MONTH");
        assertThat(my.path("data").path("periodKey").asText()).matches("\\d{4}-\\d{2}");
    }

    @Test
    @DisplayName("未登录访问配额接口返回 401")
    void anonymous_unauthorized() throws Exception {
        mockMvc.perform(get("/api/quota/my"))
                .andExpect(status().isUnauthorized());
    }

    // ========== E2E：配额耗尽后被拒 ==========

    @Test
    @DisplayName("E2E：REST 设置上限 → 真实计数 → 配额耗尽后第二次请求被 429 拒绝")
    void e2e_quotaExhausted_rejectedWith429() throws Exception {
        // 1. 管理员通过 REST 把 erin 的日请求上限设为 1
        JsonNode put = putQuota("erin",
                "{\"period\":\"DAY\",\"chatLimit\":1,\"tokenLimit\":0,\"enabled\":true}");
        assertThat(put.path("code").asInt()).isEqualTo(200);

        // 2. 第一次校验+计数通过（真实 H2 落库）
        quotaService.checkAndIncrementChat("erin");

        // 3. 第二次被 429 拒绝
        assertThatThrownBy(() -> quotaService.checkAndIncrementChat("erin"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(429))
                .hasMessageContaining("对话次数已达上限");

        // 4. REST 回读：已用 1 条，自定义配置生效中
        MvcResult got = mockMvc.perform(get("/api/admin/quota/users/erin")
                        .header("Authorization", adminToken()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode row = om.readTree(got.getResponse().getContentAsString()).path("data");
        assertThat(row.path("chatUsed").asInt()).isEqualTo(1);
        assertThat(row.path("chatLimit").asInt()).isEqualTo(1);
        assertThat(row.path("custom").asBoolean()).isTrue();

        // 5. 用户侧同步反映剩余为 0 且 nearLimit=true
        JsonNode my = getMy(bearer(95L, "erin"));
        assertThat(my.path("data").path("chatRemaining").asLong()).isEqualTo(0L);
        assertThat(my.path("data").path("nearLimit").asBoolean()).isTrue();
    }
}
