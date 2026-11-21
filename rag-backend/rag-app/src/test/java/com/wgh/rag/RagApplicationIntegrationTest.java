package com.wgh.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Spring Boot 集成测试 - 启动完整容器验证 REST API
 * 仅使用 test resources 下的 H2 内存数据库（不叠加任何 PG profile）
 */
@SpringBootTest(classes = RagApplication.class)
@AutoConfigureMockMvc
@DisplayName("Spring Boot 集成测试")
class RagApplicationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    /** 验证 Spring 应用上下文能够正常加载。 */
    @Test
    @DisplayName("应用上下文加载成功")
    void contextLoads() {
    }

    /** 验证 /actuator/health 接口返回 200 且响应包含 status 字段。 */
    @Test
    @DisplayName("健康检查 endpoint 可达")
    void healthCheck_endpoint() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").exists());
    }

    /** 验证未鉴权访问受保护接口 /api/kb 返回 401。 */
    @Test
    @DisplayName("未鉴权访问受保护接口 - 返回 401")
    void protectedEndpoint_unauthorized() throws Exception {
        mockMvc.perform(get("/api/kb"))
                .andExpect(status().isUnauthorized());
    }

    /** 验证未携带外部 token 访问 /api/auth/me 返回 401。 */
    @Test
    @DisplayName("当前用户接口 - 无 token 返回 401")
    void me_requiresExternalToken() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    /** 验证本地账号密码登录入口存在且免 token 放行：空凭据返回参数错误。 */
    @Test
    @DisplayName("本地登录入口 - 空凭据返回参数错误")
    void localLoginEndpoint_available() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(2001));
    }

    /** 验证非法外部 token 被资源服务器过滤器拒绝（401）。 */
    @Test
    @DisplayName("非法外部 token - 返回 401")
    void malformedExternalToken_rejected() throws Exception {
        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer invalid.token.value"))
                .andExpect(status().isUnauthorized());
    }

    /** 验证登录态分享页 /api/chat/share/** 未鉴权时返回 401（该路径不是匿名公开入口）。 */
    @Test
    @DisplayName("登录态分享页 - 未鉴权返回 401")
    void authenticatedSharePage_requiresAuth() throws Exception {
        // /api/chat/share/{token} 是登录态分享页（userIdentifier 取当前登录用户），
        // 匿名public分享入口在 /api/public/share/{token}
        mockMvc.perform(get("/api/chat/share/nonexistent_token_xxx"))
                .andExpect(status().isUnauthorized());
    }

    /** 验证匿名公开分享入口无需登录可达，但不存在的 token 仍由业务层校验并返回错误码。 */
    @Test
    @DisplayName("匿名公开分享入口 - 无需鉴权但仍校验 token")
    void anonymousPublicShareEndpoint_reachableWithoutAuth() throws Exception {
        mockMvc.perform(get("/api/public/share/nonexistent_token_xxx"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(
                        com.wgh.rag.common.result.ResultCode.RESOURCE_NOT_FOUND.getCode()));
    }
}
