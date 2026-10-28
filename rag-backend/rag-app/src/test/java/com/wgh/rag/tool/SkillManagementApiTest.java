package com.wgh.rag.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.RagApplication;
import com.wgh.rag.tool.entity.ToolConfig;
import com.wgh.rag.tool.executor.SkillLlmClient;
import com.wgh.rag.tool.executor.ToolExecutionEngine;
import com.wgh.rag.tool.service.SkillFileService;
import com.wgh.rag.tool.service.ToolConfigService;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Skill 全栈集成测试（最高层）—— 真实 Spring 容器 + MockMvc HTTP + H2 DB + 真实磁盘
 * <p>
 * 覆盖链路：
 *   HTTP API → SkillFileService → 磁盘 SKILL.md
 *            → import → ToolConfig 落库（H2）
 *            → ToolExecutionEngine.loadWindowTools(DB 记录) → execute → LLM
 * <p>
 * 与 SkillToolExecutorTest（executor 单元级）、SkillToolExecutorEngineTest（引擎级，手工构造对象）的区别：
 * 本类所有 bean 由 Spring 真实装配，仅 LLM 出口用 @MockitoBean 拦截。
 * <p>
 * 注意：文件名故意不用 *IntegrationTest 后缀（surefire excludes 该模式）。
 * DB 操作靠 @Transactional 自动回滚；磁盘目录在每个用例前后手动清理。
 */
@SpringBootTest(classes = RagApplication.class,
        properties = "wgh.skill.base-path=target/test-skills-api")
@AutoConfigureMockMvc
@Transactional
@DisplayName("Skill 全栈集成测试（REST → 磁盘 → DB → 引擎）")
class SkillManagementApiTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ToolConfigService toolConfigService;
    @Autowired private ToolExecutionEngine toolExecutionEngine;
    @Autowired private SkillFileService skillFileService;

    /** 拦截 LLM 出口，捕获调用参数 */
    @MockitoBean
    private SkillLlmClient skillLlmClient;

    private final ObjectMapper om = new ObjectMapper();
    private final Path skillBase = Paths.get("target/test-skills-api").toAbsolutePath().normalize();

    private String token;
    private AtomicReference<long[]> capturedModelId;
    private AtomicReference<String> capturedSystem;
    private AtomicReference<String> capturedUser;

    @BeforeEach
    void setup() throws Exception {
        // 1. 登录拿 JWT
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin\"}"))
                .andExpect(status().isOk())
                .andReturn();
        token = "Bearer " + om.readTree(login.getResponse().getContentAsString())
                .path("data").path("token").asText();
        assertThat(token).startsWith("Bearer ey");

        // 2. LLM stub：返回 userMessage 便于断言，同时捕获三参数
        capturedModelId = new AtomicReference<>();
        capturedSystem = new AtomicReference<>();
        capturedUser = new AtomicReference<>();
        when(skillLlmClient.callSubModel(any(), anyString(), anyString()))
                .thenAnswer(inv -> {
                    capturedModelId.set(new long[]{inv.getArgument(0)});
                    capturedSystem.set(inv.getArgument(1));
                    capturedUser.set(inv.getArgument(2));
                    return "LLM:" + inv.getArgument(2);
                });

        // 3. 清空磁盘测试目录
        deleteRecursively(skillBase);
        Files.createDirectories(skillBase);
    }

    @AfterEach
    void cleanup() throws IOException {
        toolExecutionEngine.clearWindowTools();
        deleteRecursively(skillBase);
    }

    private void deleteRecursively(Path dir) throws IOException {
        if (dir == null || !Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
        }
    }

    // ========== 1. REST API 层：建目录 → 写内容 → 读内容 ==========

    /** REST 建目录、保存内容后再读取，验证默认文件名、front matter 解析与原文返回 */
    @Test
    @DisplayName("REST 全链路：create-dir → save → content 读取 + front matter 解析")
    void restApi_createSaveRead_roundtrip() throws Exception {
        // 创建目录
        mockMvc.perform(post("/api/chat/tools/skill/create-dir")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dir\":\"weather\",\"displayName\":\"天气助手\",\"description\":\"查询天气\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)));

        assertThat(Files.exists(skillBase.resolve("weather/SKILL.md"))).isTrue();

        // 保存自定义内容（含 front matter + 分隔符）
        String skillMd = """
                ---
                subModelId: 5
                description: 天气查询
                ---
                ---SYSTEM---
                你是天气助手。
                ---USER---
                查询 {{city}} 天气
                """;
        mockMvc.perform(post("/api/chat/tools/skill/save")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(java.util.Map.of(
                                "dir", "weather", "content", skillMd))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)));

        // 读取回来验证解析
        mockMvc.perform(get("/api/chat/tools/skill/content")
                        .header("Authorization", token)
                        .param("dir", "weather"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mainFile", is("SKILL.md")))
                .andExpect(jsonPath("$.data.frontMatter.subModelId", is("5")))
                .andExpect(jsonPath("$.data.frontMatter.description", is("天气查询")))
                .andExpect(jsonPath("$.data.content", containsString("---SYSTEM---")));
    }

    /** GET dirs 扫描磁盘并列出已创建的 skill 目录与 basePath */
    @Test
    @DisplayName("GET dirs 列出磁盘上的 skill 目录")
    void scanDirs_listsCreatedSkill() throws Exception {
        Files.createDirectories(skillBase.resolve("finance"));
        Files.writeString(skillBase.resolve("finance/SKILL.md"), "金融助手");

        mockMvc.perform(get("/api/chat/tools/skill/dirs")
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.skills", hasSize(1)))
                .andExpect(jsonPath("$.data.skills[0].dirName", is("finance")))
                .andExpect(jsonPath("$.data.basePath", is(skillBase.toString())));
    }

    /** 目录参数包含 ../ 路径穿越时返回非 200 的业务错误码 */
    @Test
    @DisplayName("路径穿越防护：../../ 被拒绝")
    void pathTraversal_rejected() throws Exception {
        mockMvc.perform(get("/api/chat/tools/skill/content")
                        .header("Authorization", token)
                        .param("dir", "../../etc"))
                .andExpect(status().isOk())   // 统一 Result 包装，业务错误 code != 200
                .andExpect(jsonPath("$.code", not(200)));
    }

    /** 未携带 token 访问 skill 接口时返回 401 */
    @Test
    @DisplayName("无 token 访问 skill API → 401")
    void noToken_unauthorized() throws Exception {
        mockMvc.perform(get("/api/chat/tools/skill/dirs"))
                .andExpect(status().isUnauthorized());
    }

    // ========== 2. import：磁盘 → DB ==========

    /** import 把磁盘 skill 落库为 SKILL 工具，executorConfig 由 front matter 自动构造 */
    @Test
    @DisplayName("import 把磁盘 skill 导入为 ToolConfig DB 记录（executorConfig 自动构造）")
    void importSkill_createsDbRecord() throws Exception {
        Files.createDirectories(skillBase.resolve("translator"));
        Files.writeString(skillBase.resolve("translator/SKILL.md"), """
                ---
                subModelId: 8
                description: 翻译助手
                ---
                你是翻译助手 {{text}}
                """);

        mockMvc.perform(post("/api/chat/tools/skill/import")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dir\":\"translator\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data.duplicated", is(false)))
                .andExpect(jsonPath("$.data.tool.toolType", is("SKILL")))
                .andExpect(jsonPath("$.data.tool.description", is("翻译助手")));

        // 验证 DB 里的记录
        List<ToolConfig> skills = toolConfigService.listByType("SKILL");
        assertThat(skills).hasSize(1);
        ToolConfig saved = skills.get(0);
        assertThat(saved.getName()).isEqualTo("translator");
        assertThat(saved.getStatus()).isEqualTo(1);

        JsonNode execCfg = om.readTree(saved.getExecutorConfig());
        assertThat(execCfg.path("mode").asText()).isEqualTo("directory");
        assertThat(execCfg.path("skillPath").asText())
                .isEqualTo(skillBase.resolve("translator").toString());
        assertThat(execCfg.path("subModelId").asLong()).isEqualTo(8L);   // front matter 解析而来
        assertThat(execCfg.path("includeResources").asBoolean()).isTrue();
    }

    /** 同名 skill 重复导入时返回 duplicated=true 且不覆盖已有记录 */
    @Test
    @DisplayName("import：同名 skill 已存在时不创建/不更新，返回 duplicated=true 与已有记录")
    void importSkill_duplicated() throws Exception {
        // 准备磁盘 skill
        Files.createDirectories(skillBase.resolve("weather"));
        Files.writeString(skillBase.resolve("weather/SKILL.md"), """
                ---
                description: 天气查询
                ---
                查询天气 {{city}}
                """);

        // 首次导入
        mockMvc.perform(post("/api/chat/tools/skill/import")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dir\":\"weather\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.duplicated", is(false)));

        // 修改磁盘文件内容（用作"是否被覆盖"的探针）
        String firstDefinition = toolConfigService.listByType("SKILL").get(0).getDefinition();

        // 再次导入同名 skill —— 应跳过且 DB 未变
        mockMvc.perform(post("/api/chat/tools/skill/import")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dir\":\"weather\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data.duplicated", is(true)))
                .andExpect(jsonPath("$.data.tool.name", is("weather")))
                .andExpect(jsonPath("$.data.hint", containsString("未做导入或更新")));

        // DB 仍只有 1 条，且 definition 未被覆盖
        List<ToolConfig> skills = toolConfigService.listByType("SKILL");
        assertThat(skills).hasSize(1);
        assertThat(skills.get(0).getDefinition()).isEqualTo(firstDefinition);
    }

    // ========== 3. 最高层：HTTP 建 skill → import 落库 → 引擎从 DB 加载 → 执行 → LLM ==========

    /** 全栈链路：REST 建目录保存 → import 落库 → 引擎从 DB 加载执行 → LLM 收到渲染后 prompt 与继承模型 */
    @Test
    @DisplayName("全栈链路：REST 建目录/保存 → import 落库 → engine 从 DB 加载执行 → LLM 收到渲染后 prompt")
    void fullStack_restToDbToEngineToLlm() throws Exception {
        // --- 通过 REST API 建 skill ---
        mockMvc.perform(post("/api/chat/tools/skill/create-dir")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dir\":\"code-review\",\"displayName\":\"代码审查\"}"))
                .andExpect(status().isOk());

        String skillMd = """
                ---
                subModelDefault: true
                ---
                ---SYSTEM---
                你是代码审查助手。
                ---USER---
                审查 {{input}}
                """;
        mockMvc.perform(post("/api/chat/tools/skill/save")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(java.util.Map.of(
                                "dir", "code-review", "content", skillMd))))
                .andExpect(status().isOk());

        // --- import 落库 ---
        mockMvc.perform(post("/api/chat/tools/skill/import")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dir\":\"code-review\"}"))
                .andExpect(status().isOk());

        // --- 模拟聊天窗口：从 DB 加载工具 + 窗口默认模型 42 ---
        List<ToolConfig> dbTools = toolConfigService.listByType("SKILL");
        assertThat(dbTools).hasSize(1);
        var specs = toolExecutionEngine.loadWindowTools(dbTools, 42L);
        assertThat(specs).hasSize(1);
        assertThat(specs.get(0).name()).isEqualTo("code-review");
        try {
            // --- 引擎执行（$windowDefaultModelId=42 注入 + front matter subModelDefault 继承） ---
            ToolExecutionResultMessage result = toolExecutionEngine.execute(
                    ToolExecutionRequest.builder()
                            .id("c1").name("code-review")
                            .arguments("{\"input\":\"这段代码\"}").build());

            assertThat(result.text()).isEqualTo("LLM:审查 这段代码");
            assertThat(capturedModelId.get()[0]).isEqualTo(42L);          // 继承窗口默认模型
            assertThat(capturedSystem.get()).isEqualTo("你是代码审查助手。"); // 分隔符正确拆分
            assertThat(capturedUser.get()).isEqualTo("审查 这段代码");       // 模板渲染
        } finally {
            toolExecutionEngine.clearWindowTools();
        }
    }

    /** import 后通过 REST 修改磁盘内容，引擎下一次执行立即读到新版本 */
    @Test
    @DisplayName("全栈动态重载：import 后改磁盘文件，引擎下一次执行立即生效")
    void fullStack_diskEditVisibleToEngineImmediately() throws Exception {
        Files.createDirectories(skillBase.resolve("dyn"));
        Files.writeString(skillBase.resolve("dyn/SKILL.md"), "版本1 {{input}}");

        mockMvc.perform(post("/api/chat/tools/skill/import")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dir\":\"dyn\",\"subModelId\":\"1\"}"))
                .andExpect(status().isOk());

        List<ToolConfig> dbTools = toolConfigService.listByType("SKILL");
        toolExecutionEngine.loadWindowTools(dbTools, null);
        try {
            toolExecutionEngine.execute(ToolExecutionRequest.builder()
                    .id("1").name("dyn").arguments("{\"input\":\"a\"}").build());
            assertThat(capturedSystem.get()).isEqualTo("版本1 a");

            // 通过 REST API 在线改内容（模拟管理页面编辑保存）
            mockMvc.perform(post("/api/chat/tools/skill/save")
                            .header("Authorization", token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(java.util.Map.of(
                                    "dir", "dyn", "content", "版本2 {{input}}"))))
                    .andExpect(status().isOk());

            // 引擎无需重新加载，directory 模式实时读盘
            toolExecutionEngine.execute(ToolExecutionRequest.builder()
                    .id("2").name("dyn").arguments("{\"input\":\"b\"}").build());
            assertThat(capturedSystem.get()).isEqualTo("版本2 b");
        } finally {
            toolExecutionEngine.clearWindowTools();
        }
    }

    /** 通过 REST 删除 skill 目录后引擎执行返回「目录不存在」错误 */
    @Test
    @DisplayName("delete-dir 后引擎执行返回目录不存在错误")
    void deleteDir_engineReturnsLoadError() throws Exception {
        Files.createDirectories(skillBase.resolve("temp"));
        Files.writeString(skillBase.resolve("temp/SKILL.md"), "临时 prompt");

        mockMvc.perform(post("/api/chat/tools/skill/import")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dir\":\"temp\",\"subModelId\":\"1\"}"))
                .andExpect(status().isOk());

        toolExecutionEngine.loadWindowTools(toolConfigService.listByType("SKILL"), null);
        try {
            // 通过 REST 删目录
            mockMvc.perform(delete("/api/chat/tools/skill/dir")
                            .header("Authorization", token)
                            .param("dir", "temp"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code", is(200)));
            assertThat(Files.exists(skillBase.resolve("temp"))).isFalse();

            ToolExecutionResultMessage result = toolExecutionEngine.execute(
                    ToolExecutionRequest.builder().id("1").name("temp").arguments("{}").build());
            assertThat(result.text()).contains("目录不存在");
        } finally {
            toolExecutionEngine.clearWindowTools();
        }
    }
}
