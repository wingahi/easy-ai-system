package com.wgh.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.is;
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

    @Test
    @DisplayName("应用上下文加载成功")
    void contextLoads() {
    }

    @Test
    @DisplayName("健康检查 endpoint 可达")
    void healthCheck_endpoint() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").exists());
    }

    @Test
    @DisplayName("未鉴权访问受保护接口 - 返回 401")
    void protectedEndpoint_unauthorized() throws Exception {
        mockMvc.perform(get("/api/kb"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("登录接口 - admin/admin 成功获取 token")
    void login_success() throws Exception {
        String loginBody = """
                {"username":"admin","password":"admin"}
                """;

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data.token").exists())
                .andExpect(jsonPath("$.data.token").isNotEmpty());
    }

    @Test
    @DisplayName("登录接口 - 错误密码")
    void login_wrongPassword() throws Exception {
        String loginBody = """
                {"username":"admin","password":"wrong"}
                """;

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(500)));
    }

    @Test
    @DisplayName("分享接口不需要鉴权")
    void shareEndpoint_noAuth() throws Exception {
        // /api/chat/share/{token} 不存在 token → Service 返回 Result 但 HTTP 200（不 401）
        mockMvc.perform(get("/api/chat/share/nonexistent_token_xxx"))
                .andExpect(status().isOk());
    }
}
