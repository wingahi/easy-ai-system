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

    /** 验证本地登录端点已移除：无 token 访问 /api/auth/login 返回 401。 */
    @Test
    @DisplayName("本地登录端点已移除 - 返回 401")
    void localLoginEndpoint_removed() throws Exception {
        mockMvc.perform(post("/api/auth/login"))
                .andExpect(status().isUnauthorized());
    }

    /** 验证非法外部 token 被资源服务器过滤器拒绝（401）。 */
    @Test
    @DisplayName("非法外部 token - 返回 401")
    void malformedExternalToken_rejected() throws Exception {
        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer invalid.token.value"))
                .andExpect(status().isUnauthorized());
    }

    /** 验证访问不存在的分享链接无需鉴权，返回 HTTP 200 而非 401。 */
    @Test
    @DisplayName("分享接口不需要鉴权")
    void shareEndpoint_noAuth() throws Exception {
        // /api/chat/share/{token} 不存在 token → Service 返回 Result 但 HTTP 200（不 401）
        mockMvc.perform(get("/api/chat/share/nonexistent_token_xxx"))
                .andExpect(status().isOk());
    }
}
