package com.wgh.rag.tool;

import com.wgh.rag.tool.executor.SkillLlmClient;
import com.wgh.rag.tool.executor.SkillToolExecutor;
import com.wgh.rag.tool.executor.ToolExecutionEngine;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * SkillToolExecutor 单元测试 —— inline / directory 双模式 + 模板渲染
 */
@DisplayName("SkillToolExecutor")
class SkillToolExecutorTest {

    private SkillLlmClient mockLlmClient;
    private SkillToolExecutor executor;

    @BeforeEach
    void setup() {
        mockLlmClient = mock(SkillLlmClient.class);
        executor = new SkillToolExecutor(mockLlmClient);
    }

    // ========== 基础检查 ==========

    @Test
    void supportedToolType_returnsSKILL() {
        assertThat(executor.supportedToolType()).isEqualTo("SKILL");
    }

    // ========== inline 模式 ==========

    @Nested
    @DisplayName("inline 模式（promptTemplate 直接写）")
    class InlineMode {

        @Test
        void basicInline_rendersTemplateAndCallsSubModel() {
            // GIVEN —— 先不匹配具体参数，验证 mock 能被调用
            AtomicReference<String> capturedPrompt = new AtomicReference<>();
            AtomicReference<Long> capturedModelId = new AtomicReference<>();
            when(mockLlmClient.callSubModel(anyLong(), anyString(), anyString()))
                    .thenAnswer(inv -> {
                        capturedModelId.set(inv.getArgument(0));
                        capturedPrompt.set(inv.getArgument(1));
                        return "北京晴朗 25°C";
                    });

            Map<String, Object> config = Map.of(
                    "mode", "inline",
                    "promptTemplate", "你是天气助手，{{city}} 今天天气如何？",
                    "subModelId", 2
            );
            ToolExecutionRequest req = ToolExecutionRequest.builder()
                    .id("t1").name("query_weather")
                    .arguments("{\"city\":\"北京\"}").build();

            // WHEN
            ToolExecutionResultMessage result = executor.execute(config, req);

            // THEN
            assertThat(result.text()).isEqualTo("北京晴朗 25°C");
            assertThat(capturedModelId.get()).isEqualTo(2L);
            // 模板 "{{city}} " 里空格跟在占位符后面，替换后北京后面带空格
            assertThat(capturedPrompt.get()).isEqualTo("你是天气助手，北京 今天天气如何？");
        }

        @Test
        void inline_noSubModelId_returnsError() {
            // SkillToolExecutor 不拦截 null subModelId（交给 LlmClient 内部处理）
            when(mockLlmClient.callSubModel(isNull(), anyString(), anyString()))
                    .thenReturn("Error: Skill executorConfig 缺少 subModelId 字段");

            Map<String, Object> config = Map.of(
                    "mode", "inline",
                    "promptTemplate", "你好 {{name}}"
                    // 没有 subModelId → null
            );
            ToolExecutionRequest req = ToolExecutionRequest.builder()
                    .id("t1").name("greet").arguments("{\"name\":\"小明\"}").build();

            ToolExecutionResultMessage result = executor.execute(config, req);

            assertThat(result.text()).contains("subModelId");
        }

        @Test
        void inline_noPromptTemplate_returnsError() {
            Map<String, Object> config = Map.of(
                    "mode", "inline",
                    "subModelId", 1
                    // 没有 promptTemplate → early return，不会调 LLM
            );
            ToolExecutionRequest req = ToolExecutionRequest.builder()
                    .id("t1").name("foo").arguments("{}").build();

            ToolExecutionResultMessage result = executor.execute(config, req);

            assertThat(result.text()).contains("promptTemplate");
            verify(mockLlmClient, never()).callSubModel(any(), anyString(), anyString());
        }

        @Test
        void inline_emptyArguments_handlesGracefully() {
            when(mockLlmClient.callSubModel(eq(1L), anyString(), anyString())).thenReturn("你好！");

            Map<String, Object> config = Map.of(
                    "mode", "inline",
                    "promptTemplate", "你好！",
                    "subModelId", 1
            );
            ToolExecutionRequest req = ToolExecutionRequest.builder()
                    .id("t1").name("foo").arguments("").build();

            ToolExecutionResultMessage result = executor.execute(config, req);

            assertThat(result.text()).isEqualTo("你好！");
        }

        @Test
        void inline_invalidArgumentsJson_handlesGracefully() {
            // parseArguments 失败返回空 map → args.isEmpty()=true → userMessage=""
            // {{name}} 没被替换（空 map 里没有）
            when(mockLlmClient.callSubModel(eq(1L), anyString(), anyString())).thenReturn("hi");

            Map<String, Object> config = Map.of(
                    "mode", "inline",
                    "promptTemplate", "你好 {{name}}",
                    "subModelId", 1
            );
            ToolExecutionRequest req = ToolExecutionRequest.builder()
                    .id("t1").name("foo").arguments("{bad json}").build();

            ToolExecutionResultMessage result = executor.execute(config, req);

            assertThat(result.text()).isEqualTo("hi");
        }

        @Test
        void inline_llmError_returnsErrorMessage() {
            when(mockLlmClient.callSubModel(eq(1L), anyString(), anyString()))
                    .thenReturn("Error: 模型不可用");

            Map<String, Object> config = Map.of(
                    "mode", "inline",
                    "promptTemplate", "test",
                    "subModelId", 1
            );
            ToolExecutionRequest req = ToolExecutionRequest.builder()
                    .id("t1").name("foo").arguments("{}").build();

            ToolExecutionResultMessage result = executor.execute(config, req);

            assertThat(result.text()).contains("Error");
        }
    }

    // ========== 模板渲染 ==========

    @Nested
    @DisplayName("{{var}} 模板渲染")
    class TemplateRendering {

        @Test
        void simpleVar_replacesPlaceholder() {
            AtomicReference<String> capturedPrompt = new AtomicReference<>();
            when(mockLlmClient.callSubModel(eq(1L), anyString(), any()))
                    .thenAnswer(inv -> { capturedPrompt.set(inv.getArgument(1)); return "ok"; });

            Map<String, Object> config = Map.of(
                    "mode", "inline",
                    "promptTemplate", "地点={{location}}，日期={{date}}",
                    "subModelId", 1
            );
            ToolExecutionRequest req = ToolExecutionRequest.builder()
                    .id("t1").name("x").arguments("{\"location\":\"上海\",\"date\":\"2025-01-01\"}").build();

            executor.execute(config, req);

            assertThat(capturedPrompt.get()).isEqualTo("地点=上海，日期=2025-01-01");
        }

        @Test
        void missingVar_returnsEmptyString() {
            AtomicReference<String> capturedPrompt = new AtomicReference<>();
            when(mockLlmClient.callSubModel(eq(1L), anyString(), any()))
                    .thenAnswer(inv -> { capturedPrompt.set(inv.getArgument(1)); return "ok"; });

            Map<String, Object> config = Map.of(
                    "mode", "inline",
                    "promptTemplate", "{{known}} + {{unknown_var}}",
                    "subModelId", 1
            );
            ToolExecutionRequest req = ToolExecutionRequest.builder()
                    .id("t1").name("x").arguments("{\"known\":\"A\"}").build();

            executor.execute(config, req);

            // {{unknown_var}} 保持原样还是被清空？当前实现是清空（resolveVar 返回 null → ""）
            assertThat(capturedPrompt.get()).isEqualTo("A + ");
        }

        @Test
        void noPlaceholder_templatePassesThrough() {
            AtomicReference<String> capturedPrompt = new AtomicReference<>();
            when(mockLlmClient.callSubModel(eq(1L), anyString(), any()))
                    .thenAnswer(inv -> { capturedPrompt.set(inv.getArgument(1)); return "ok"; });

            Map<String, Object> config = Map.of(
                    "mode", "inline",
                    "promptTemplate", "你是一个翻译助手",
                    "subModelId", 1
            );
            ToolExecutionRequest req = ToolExecutionRequest.builder()
                    .id("t1").name("x").arguments("{\"text\":\"hello\"}").build();

            executor.execute(config, req);

            assertThat(capturedPrompt.get()).isEqualTo("你是一个翻译助手");
        }
    }

    // ========== directory 模式 ==========

    @Nested
    @DisplayName("directory 模式（从磁盘加载 prompt）")
    class DirectoryMode {

        private Path tempSkillDir;

        @BeforeEach
        void setupTempDir() throws IOException {
            tempSkillDir = Files.createTempDirectory("skill-test-");
        }

        @Test
        void loadsSKILLmd_first() throws IOException {
            Files.writeString(tempSkillDir.resolve("SKILL.md"), "# 天气查询\n{{city}} 今天如何？");

            AtomicReference<String> capturedPrompt = new AtomicReference<>();
            when(mockLlmClient.callSubModel(eq(3L), anyString(), any()))
                    .thenAnswer(inv -> { capturedPrompt.set(inv.getArgument(1)); return "ok"; });

            Map<String, Object> config = Map.of(
                    "mode", "directory",
                    "skillPath", tempSkillDir.toString(),
                    "subModelId", 3
            );
            ToolExecutionRequest req = ToolExecutionRequest.builder()
                    .id("t1").name("query_weather").arguments("{\"city\":\"北京\"}").build();

            executor.execute(config, req);

            assertThat(capturedPrompt.get()).contains("北京");
        }

        @Test
        void fallsBackToPromptMd_whenNoSKILLmd() throws IOException {
            Files.writeString(tempSkillDir.resolve("prompt.md"), "fallback prompt {{name}}");

            AtomicReference<String> capturedPrompt = new AtomicReference<>();
            when(mockLlmClient.callSubModel(eq(1L), anyString(), any()))
                    .thenAnswer(inv -> { capturedPrompt.set(inv.getArgument(1)); return "ok"; });

            Map<String, Object> config = Map.of(
                    "mode", "directory",
                    "skillPath", tempSkillDir.toString(),
                    "subModelId", 1
            );
            ToolExecutionRequest req = ToolExecutionRequest.builder()
                    .id("t1").name("x").arguments("{\"name\":\"World\"}").build();

            executor.execute(config, req);

            assertThat(capturedPrompt.get()).isEqualTo("fallback prompt World");
        }

        @Test
        void missingSkillPath_returnsError() {
            Map<String, Object> config = Map.of(
                    "mode", "directory",
                    "subModelId", 1
                    // 没有 skillPath
            );
            ToolExecutionRequest req = ToolExecutionRequest.builder()
                    .id("t1").name("x").arguments("{}").build();

            ToolExecutionResultMessage result = executor.execute(config, req);

            assertThat(result.text()).contains("skillPath");
        }

        @Test
        void nonexistentDirectory_returnsError() {
            Map<String, Object> config = Map.of(
                    "mode", "directory",
                    "skillPath", "/nonexistent/path/xyz",
                    "subModelId", 1
            );
            ToolExecutionRequest req = ToolExecutionRequest.builder()
                    .id("t1").name("x").arguments("{}").build();

            ToolExecutionResultMessage result = executor.execute(config, req);

            assertThat(result.text()).contains("目录不存在");
        }

        @Test
        void directoryWithNoPromptFiles_returnsError() throws IOException {
            // 空目录
            Files.createDirectories(tempSkillDir.resolve("subdir"));

            Map<String, Object> config = Map.of(
                    "mode", "directory",
                    "skillPath", tempSkillDir.toString(),
                    "subModelId", 1
            );
            ToolExecutionRequest req = ToolExecutionRequest.builder()
                    .id("t1").name("x").arguments("{}").build();

            ToolExecutionResultMessage result = executor.execute(config, req);

            assertThat(result.text()).contains("SKILL.md");
        }

        @Test
        void readsPromptOnEveryCall_dynamicFileLoading() throws IOException {
            // 验证"运行时实时加载" —— 文件改了立即生效
            Path skillFile = tempSkillDir.resolve("SKILL.md");
            Files.writeString(skillFile, "初始 prompt {{v}}");

            when(mockLlmClient.callSubModel(eq(1L), anyString(), any()))
                    .thenAnswer(inv -> inv.getArgument(1));

            Map<String, Object> config = Map.of(
                    "mode", "directory",
                    "skillPath", tempSkillDir.toString(),
                    "subModelId", 1
            );

            ToolExecutionRequest req1 = ToolExecutionRequest.builder()
                    .id("t1").name("x").arguments("{\"v\":\"first\"}").build();
            ToolExecutionResultMessage r1 = executor.execute(config, req1);
            assertThat(r1.text()).isEqualTo("初始 prompt first");

            // 文件改了！
            Files.writeString(skillFile, "修改后 prompt {{v}}");

            ToolExecutionRequest req2 = ToolExecutionRequest.builder()
                    .id("t2").name("x").arguments("{\"v\":\"second\"}").build();
            ToolExecutionResultMessage r2 = executor.execute(config, req2);

            assertThat(r2.text()).isEqualTo("修改后 prompt second");
        }
    }

    // ========== directory 高级特性（front matter / 分隔符 / 资源合并 / 继承链） ==========

    @Nested
    @DisplayName("directory 高级特性 —— front matter + 分隔符 + 资源合并")
    class DirectoryAdvanced {

        private Path tempSkillDir;

        /** 捕获传给 LlmClient 的三个参数 */
        record CallArgs(Long modelId, String systemPrompt, String userMessage) {}

        private AtomicReference<CallArgs> captured;

        @BeforeEach
        void setup() throws IOException {
            tempSkillDir = Files.createTempDirectory("skill-adv-");
            captured = new AtomicReference<>();
            when(mockLlmClient.callSubModel(anyLong(), anyString(), anyString()))
                    .thenAnswer(inv -> {
                        captured.set(new CallArgs(
                                inv.getArgument(0),
                                inv.getArgument(1),
                                inv.getArgument(2)
                        ));
                        return "ok";
                    });
        }

        @org.junit.jupiter.api.AfterEach
        void cleanup() throws IOException {
            if (tempSkillDir != null) {
                try (var walk = Files.walk(tempSkillDir)) {
                    walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                            .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
                }
            }
        }

        // ---- 1. YAML front matter 提取 subModelId ----

        @Test
        void yamlFrontMatter_extractsSubModelId() throws IOException {
            Files.writeString(tempSkillDir.resolve("SKILL.md"),
                    """
                    ---
                    subModelId: 5
                    description: 翻译助手
                    ---
                    你是翻译助手，请翻译文本
                    """);

            Map<String, Object> config = Map.of(
                    "mode", "directory",
                    "skillPath", tempSkillDir.toString()
                    // 注意：executorConfig 里没设 subModelId，front matter 应该自动填入
            );
            ToolExecutionRequest req = ToolExecutionRequest.builder()
                    .id("t1").name("translate").arguments("{\"text\":\"hello\"}").build();

            executor.execute(config, req);

            assertThat(captured.get()).isNotNull();
            assertThat(captured.get().modelId()).isEqualTo(5L);
            assertThat(captured.get().systemPrompt()).contains("翻译助手");
        }

        @Test
        void executorConfigSubModelId_overridesFrontMatter() throws IOException {
            Files.writeString(tempSkillDir.resolve("SKILL.md"),
                    """
                    ---
                    subModelId: 5
                    ---
                    你是谁？
                    """);

            Map<String, Object> config = Map.of(
                    "mode", "directory",
                    "skillPath", tempSkillDir.toString(),
                    "subModelId", 9  // 显式配置优先覆盖 front matter
            );
            ToolExecutionRequest req = ToolExecutionRequest.builder()
                    .id("t1").name("x").arguments("{}").build();

            executor.execute(config, req);

            assertThat(captured.get().modelId()).isEqualTo(9L);
        }

        // ---- 2. ---SYSTEM--- / ---USER--- 分隔符 ----

        @Test
        void systemUserSeparator_splitsPromptIntoTwoParts() throws IOException {
            Files.writeString(tempSkillDir.resolve("SKILL.md"),
                    """
                    ---SYSTEM---
                    你是天气查询助手，只回答中国城市天气。

                    ---USER---
                    请查询 {{city}} 的天气。
                    """);

            Map<String, Object> config = Map.of(
                    "mode", "directory",
                    "skillPath", tempSkillDir.toString(),
                    "subModelId", 1
            );
            ToolExecutionRequest req = ToolExecutionRequest.builder()
                    .id("t1").name("query_weather").arguments("{\"city\":\"上海\"}").build();

            executor.execute(config, req);

            assertThat(captured.get()).isNotNull();
            assertThat(captured.get().systemPrompt()).contains("天气查询助手");
            assertThat(captured.get().userMessage()).isEqualTo("请查询 上海 的天气。");
        }

        @Test
        void onlyUserSeparator_systemPartIsEverythingBefore() throws IOException {
            Files.writeString(tempSkillDir.resolve("SKILL.md"),
                    """
                    你是一个代码审查助手。
                    请仔细检查代码质量。

                    ---USER---
                    帮我 review 这段代码：{{code}}
                    """);

            Map<String, Object> config = Map.of(
                    "mode", "directory",
                    "skillPath", tempSkillDir.toString(),
                    "subModelId", 1
            );
            ToolExecutionRequest req = ToolExecutionRequest.builder()
                    .id("t1").name("review").arguments("{\"code\":\"var x = 1;\"}").build();

            executor.execute(config, req);

            assertThat(captured.get().systemPrompt()).contains("代码审查助手");
            assertThat(captured.get().userMessage()).isEqualTo("帮我 review 这段代码：var x = 1;");
        }

        @Test
        void noSeparator_entireContentBecomesSystemPrompt() throws IOException {
            Files.writeString(tempSkillDir.resolve("SKILL.md"), "你是一个助手，回答 {{question}}");

            Map<String, Object> config = Map.of(
                    "mode", "directory",
                    "skillPath", tempSkillDir.toString(),
                    "subModelId", 1
            );
            ToolExecutionRequest req = ToolExecutionRequest.builder()
                    .id("t1").name("ask").arguments("{\"question\":\"1+1=?\"}").build();

            executor.execute(config, req);

            assertThat(captured.get().systemPrompt()).isEqualTo("你是一个助手，回答 1+1=?");
            // 没有 userTemplate → args.toString() 格式（Map.toString）
            assertThat(captured.get().userMessage()).isEqualTo("{question=1+1=?}");
        }

        // ---- 3. references/ examples/ scripts/ 资源目录合并 ----

        @Test
        void referencesDir_mdFilesAppendedToMainPrompt() throws IOException {
            // 主 prompt
            Files.writeString(tempSkillDir.resolve("SKILL.md"),
                    """
                    ---SYSTEM---
                    你是翻译助手，必须遵守以下规则：
                    ---USER---
                    请翻译：{{text}}
                    """);

            // references 子目录
            Path refs = Files.createDirectory(tempSkillDir.resolve("references"));
            Files.writeString(refs.resolve("style-guide.md"),
                    """
                    # 翻译风格指南
                    - 技术术语保持英文
                    - 口语化表达
                    """);
            Files.writeString(refs.resolve("vocab.txt"),
                    """
                    hello → 你好
                    world → 世界
                    """);

            Map<String, Object> config = Map.of(
                    "mode", "directory",
                    "skillPath", tempSkillDir.toString(),
                    "subModelId", 1
            );
            ToolExecutionRequest req = ToolExecutionRequest.builder()
                    .id("t1").name("translate").arguments("{\"text\":\"hello world\"}").build();

            executor.execute(config, req);

            assertThat(captured.get().systemPrompt())
                    .contains("翻译助手")
                    .contains("翻译风格指南")
                    .contains("技术术语保持英文")
                    .contains("hello → 你好");
        }

        @Test
        void examplesDir_mergedAfterReferences() throws IOException {
            Path examples = Files.createDirectory(tempSkillDir.resolve("examples"));
            Path refs = Files.createDirectory(tempSkillDir.resolve("references"));

            Files.writeString(tempSkillDir.resolve("SKILL.md"), "主 prompt");
            Files.writeString(refs.resolve("ref1.md"), "参考资料 A");
            Files.writeString(examples.resolve("ex1.md"), "示例 1");

            Map<String, Object> config = Map.of(
                    "mode", "directory",
                    "skillPath", tempSkillDir.toString(),
                    "subModelId", 1
            );
            ToolExecutionRequest req = ToolExecutionRequest.builder()
                    .id("t1").name("x").arguments("{}").build();

            executor.execute(config, req);

            // references 和 examples 都被合并进 system prompt
            assertThat(captured.get().systemPrompt())
                    .contains("主 prompt")
                    .contains("参考资料 A")
                    .contains("示例 1");
        }

        @Test
        void includeResourcesFalse_skipsResourceMerge() throws IOException {
            Path refs = Files.createDirectory(tempSkillDir.resolve("references"));
            Files.writeString(tempSkillDir.resolve("SKILL.md"), "只有主 prompt");
            Files.writeString(refs.resolve("secret.md"), "机密参考资料 — 不应该出现");

            Map<String, Object> config = new HashMap<>();
            config.put("mode", "directory");
            config.put("skillPath", tempSkillDir.toString());
            config.put("subModelId", 1);
            config.put("includeResources", false);  // 关键：跳过资源

            ToolExecutionRequest req = ToolExecutionRequest.builder()
                    .id("t1").name("x").arguments("{}").build();

            executor.execute(config, req);

            assertThat(captured.get().systemPrompt())
                    .isEqualTo("只有主 prompt")
                    .doesNotContain("机密参考资料");
        }

        // ---- 4. subModelDefault 继承窗口默认模型 ----

        @Test
        void frontMatterSubModelDefaultTrue_inheritsWindowDefaultModelId() throws IOException {
            Files.writeString(tempSkillDir.resolve("SKILL.md"),
                    """
                    ---
                    subModelDefault: true
                    ---
                    你好 {{name}}
                    """);

            Map<String, Object> config = new HashMap<>();
            config.put("mode", "directory");
            config.put("skillPath", tempSkillDir.toString());
            // 没显式 subModelId，front matter 里 subModelDefault=true
            config.put(ToolExecutionEngine.WINDOW_DEFAULT_MODEL_KEY, 42L);

            ToolExecutionRequest req = ToolExecutionRequest.builder()
                    .id("t1").name("greet").arguments("{\"name\":\"小明\"}").build();

            executor.execute(config, req);

            assertThat(captured.get().modelId()).isEqualTo(42L);
            assertThat(captured.get().systemPrompt()).isEqualTo("你好 小明");
        }

        @Test
        void subModelDefaultTrue_inline_inheritsWindowDefaultModelId() {
            // inline 模式的继承链也验证一下（和 directory 共用同一逻辑）
            Map<String, Object> config = new HashMap<>();
            config.put("mode", "inline");
            config.put("promptTemplate", "hello {{n}}");
            config.put("subModelDefault", true);
            config.put(ToolExecutionEngine.WINDOW_DEFAULT_MODEL_KEY, 7L);

            ToolExecutionRequest req = ToolExecutionRequest.builder()
                    .id("t1").name("x").arguments("{\"n\":\"world\"}").build();

            executor.execute(config, req);

            assertThat(captured.get().modelId()).isEqualTo(7L);
        }

        @Test
        void subModelDefaultTrue_withoutWindowDefault_returnsError() throws IOException {
            Files.writeString(tempSkillDir.resolve("SKILL.md"),
                    """
                    ---
                    subModelDefault: true
                    ---
                    prompt
                    """);

            Map<String, Object> config = new HashMap<>();
            config.put("mode", "directory");
            config.put("skillPath", tempSkillDir.toString());
            // 没有 WINDOW_DEFAULT_MODEL_KEY → 继承链断裂

            ToolExecutionRequest req = ToolExecutionRequest.builder()
                    .id("t1").name("x").arguments("{}").build();

            ToolExecutionResultMessage result = executor.execute(config, req);

            assertThat(result.text()).contains("缺少子模型");
            verify(mockLlmClient, never()).callSubModel(any(), anyString(), anyString());
        }

        // ---- 5. README.md fallback ----

        @Test
        void readmeFallback_whenNoSKILLmdOrPromptmd() throws IOException {
            Files.writeString(tempSkillDir.resolve("README.md"), "Readme prompt {{v}}");

            Map<String, Object> config = Map.of(
                    "mode", "directory",
                    "skillPath", tempSkillDir.toString(),
                    "subModelId", 1
            );
            ToolExecutionRequest req = ToolExecutionRequest.builder()
                    .id("t1").name("x").arguments("{\"v\":\"OK\"}").build();

            executor.execute(config, req);

            assertThat(captured.get().systemPrompt()).isEqualTo("Readme prompt OK");
        }

        // ---- 6. 完整组合场景（front matter + 分隔符 + resources + 模板） ----

        @Test
        void fullScenario_combinesAllFeatures() throws IOException {
            // SKILL.md 带 front matter + 分隔符
            Files.writeString(tempSkillDir.resolve("SKILL.md"),
                    """
                    ---
                    subModelId: 8
                    ---
                    ---SYSTEM---
                    你是一个数据分析助手，擅长处理 {{domain}} 领域数据。

                    请参考 references/ 下的资料。
                    ---USER---
                    帮我分析这份 {{domain}} 数据：{{data}}
                    """);

            Path refs = Files.createDirectory(tempSkillDir.resolve("references"));
            Files.writeString(refs.resolve("methodology.md"),
                    """
                    # 分析方法论
                    1. 先清洗数据
                    2. 再做统计分析
                    3. 最后可视化
                    """);

            Map<String, Object> config = Map.of(
                    "mode", "directory",
                    "skillPath", tempSkillDir.toString()
                    // subModelId 由 front matter 提供
            );
            ToolExecutionRequest req = ToolExecutionRequest.builder()
                    .id("t1").name("analyze")
                    .arguments("{\"domain\":\"金融\",\"data\":\"AAPL 股票数据\"}").build();

            executor.execute(config, req);

            assertThat(captured.get()).isNotNull();
            assertThat(captured.get().modelId()).isEqualTo(8L);
            assertThat(captured.get().systemPrompt())
                    .contains("数据分析助手")
                    .contains("金融")           // 模板渲染
                    .contains("分析方法论")      // references 合并
                    .contains("先清洗数据");
            assertThat(captured.get().userMessage())
                    .isEqualTo("帮我分析这份 金融 数据：AAPL 股票数据");
        }
    }

    // ========== executorConfig 类型容错 ==========

    @Test
    void subModelIdAsString_parsesCorrectly() {
        AtomicReference<Long> capturedModelId = new AtomicReference<>();
        when(mockLlmClient.callSubModel(anyLong(), anyString(), any()))
                .thenAnswer(inv -> { capturedModelId.set(inv.getArgument(0)); return "ok"; });

        Map<String, Object> config = new HashMap<>();
        config.put("mode", "inline");
        config.put("promptTemplate", "test");
        config.put("subModelId", "7");  // 字符串 "7" 而非数字 7

        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .id("t1").name("x").arguments("{}").build();

        executor.execute(config, req);

        assertThat(capturedModelId.get()).isEqualTo(7L);
    }
}
