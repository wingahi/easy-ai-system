package com.wgh.rag;

import com.wgh.rag.auth.security.LocalTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 知识库标签 / 文件列表接口的鉴权、参数与不存在 kb 的回归测试。
 */
@SpringBootTest(classes = RagApplication.class, properties = {
        // 测试库补足 wgh_user 的本地账号列与知识库实体列，避免初始管理员查询报 Column "TRIAL" not found
        "spring.sql.init.schema-locations=classpath:schema.sql,classpath:schema-auth-local.sql,classpath:schema-kb-endpoint.sql"
})
@AutoConfigureMockMvc
@DisplayName("知识库标签与文件接口回归测试")
class KnowledgeBaseEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LocalTokenService localTokenService;

    private String adminToken;
    private String viewerToken;

    @BeforeEach
    void setUp() {
        adminToken = localTokenService.issue("admin", 1L, Set.of("admin"), 1).token();
        viewerToken = localTokenService.issue("viewer", 3L, Set.of("viewer"), 1).token();
    }

    @Test
    @DisplayName("无 token 访问标签接口：HTTP 401")
    void listKbTags_withoutToken() throws Exception {
        mockMvc.perform(get("/api/kb/3/tags"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("无 token 访问文件接口：HTTP 401")
    void listFiles_withoutToken() throws Exception {
        mockMvc.perform(get("/api/kb/1/files"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("kb 不存在：标签接口返回业务码 3001 而非 500")
    void listKbTags_missingKb() throws Exception {
        mockMvc.perform(get("/api/kb/99999/tags")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(3001))
                .andExpect(jsonPath("$.message").value("知识库不存在"));
    }

    @Test
    @DisplayName("kb 不存在：文件接口返回业务码 3001 而非 500")
    void listFiles_missingKb() throws Exception {
        mockMvc.perform(get("/api/kb/99999/files")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(3001))
                .andExpect(jsonPath("$.message").value("知识库不存在"));
    }

    @Test
    @DisplayName("viewer 缺少 kb:write：设置知识库标签返回业务码 1004")
    void setKbTags_viewerForbidden() throws Exception {
        mockMvc.perform(put("/api/kb/3/tags")
                        .header("Authorization", "Bearer " + viewerToken)
                        .contentType("application/json")
                        .content("{\"tagIds\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1004));
    }

    @Test
    @DisplayName("kbId 非数字：返回 HTTP 400 而不是 500")
    void listFiles_invalidKbId() throws Exception {
        mockMvc.perform(get("/api/kb/abc/files")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001));
    }
}
