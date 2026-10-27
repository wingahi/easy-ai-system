package com.wgh.rag.tool;

import com.wgh.rag.tool.entity.ToolConfig;
import com.wgh.rag.tool.executor.SkillToolExecutor;
import com.wgh.rag.tool.executor.ToolExecutionEngine;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SkillToolExecutor 高层集成测试 —— 走 ToolExecutionEngine 全链路
 * <p>
 * 与 SkillToolExecutorTest（直接调 executor.execute）不同，这里：
 * <ul>
 *   <li>构造真实 ToolConfig（definition + executorConfig JSON 字符串）</li>
 *   <li>通过 engine.loadWindowTools() 注册窗口工具</li>
 *   <li>通过 engine.execute() 分发（含 $windowDefaultModelId 注入）</li>
 *   <li>验证窗口隔离、动态重载、禁用过滤、definition 解析失败容错</li>
 * </ul>
 * 真实临时目录读写磁盘；SkillLlmClient 用 lambda stub 捕获参数。
 */
@DisplayName("SkillToolExecutor 引擎级集成测试")
class SkillToolExecutorEngineTest {

    /** 捕获 LlmClient 调用参数 */
    record LlmCall(Long modelId, String systemPrompt, String userMessage) {}

    private AtomicReference<LlmCall> captured;
    private ToolExecutionEngine engine;
    private Path tempSkillDir;

    @BeforeEach
    void setup() throws IOException {
        tempSkillDir = Files.createTempDirectory("skill-engine-");
        captured = new AtomicReference<>();
        // lambda 实现 SkillLlmClient 函数式接口，返回 "LLM:" + userMessage 便于断言
        SkillToolExecutor skillExecutor = new SkillToolExecutor((modelId, sys, user) -> {
            captured.set(new LlmCall(modelId, sys, user));
            return "LLM:" + user;
        });
        engine = new ToolExecutionEngine(List.of(skillExecutor));
    }

    @AfterEach
    void cleanup() throws IOException {
        engine.clearWindowTools();   // ThreadLocal 必须清理，防止跨测试污染
        if (tempSkillDir != null && Files.exists(tempSkillDir)) {
            try (var walk = Files.walk(tempSkillDir)) {
                walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                        .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
            }
        }
    }

    // ========== 辅助方法 ==========

    /** 构造一条 SKILL 类型的 ToolConfig（definition/executorConfig 为真实 JSON 字符串） */
    private ToolConfig skillToolConfig(String toolName, String executorConfigJson) {
        ToolConfig cfg = new ToolConfig();
        cfg.setName(toolName);
        cfg.setToolType("SKILL");
        cfg.setDescription(toolName + " 工具");
        cfg.setStatus(1);
        cfg.setDefinition("""
                {
                  "type": "function",
                  "function": {
                    "name": "%s",
                    "description": "%s 工具",
                    "parameters": {
                      "type": "object",
                      "properties": {
                        "input": { "type": "string", "description": "用户输入" }
                      },
                      "required": ["input"]
                    }
                  }
                }
                """.formatted(toolName, toolName));
        cfg.setExecutorConfig(executorConfigJson);
        return cfg;
    }

    private ToolExecutionRequest req(String toolName, String argsJson) {
        return ToolExecutionRequest.builder()
                .id("call-1").name(toolName).arguments(argsJson).build();
    }

    // ========== 1. 全链路：ToolConfig → engine → SkillToolExecutor → LLM ==========

    @Nested
    @DisplayName("全链路执行")
    class FullChain {

        @Test
        void directorySkill_throughEngine_rendersTemplateAndCallsLlm() throws IOException {
            Files.writeString(tempSkillDir.resolve("SKILL.md"),
                    """
                    ---SYSTEM---
                    你是天气助手。
                    ---USER---
                    查询 {{input}} 的天气
                    """);

            ToolConfig cfg = skillToolConfig("query_weather",
                    """
                    {"mode":"directory","skillPath":"%s","subModelId":3}
                    """.formatted(tempSkillDir.toString().replace("\\", "\\\\")));

            engine.loadWindowTools(List.of(cfg));
            ToolExecutionResultMessage result = engine.execute(req("query_weather", "{\"input\":\"北京\"}"));

            assertThat(result.text()).isEqualTo("LLM:查询 北京 的天气");
            assertThat(captured.get()).isNotNull();
            assertThat(captured.get().modelId()).isEqualTo(3L);
            assertThat(captured.get().systemPrompt()).isEqualTo("你是天气助手。");
            assertThat(captured.get().userMessage()).isEqualTo("查询 北京 的天气");
        }

        @Test
        void inlineSkill_throughEngine_works() {
            ToolConfig cfg = skillToolConfig("greeter",
                    """
                    {"mode":"inline","promptTemplate":"你好 {{input}}","subModelId":1}
                    """);

            engine.loadWindowTools(List.of(cfg));
            ToolExecutionResultMessage result = engine.execute(req("greeter", "{\"input\":\"世界\"}"));

            assertThat(result.text()).isEqualTo("LLM:{input=世界}");
            assertThat(captured.get().systemPrompt()).isEqualTo("你好 世界");
        }

        @Test
        void multipleSkills_eachRoutedByToolName() throws IOException {
            Path dirA = Files.createDirectory(tempSkillDir.resolve("skill-a"));
            Path dirB = Files.createDirectory(tempSkillDir.resolve("skill-b"));
            Files.writeString(dirA.resolve("SKILL.md"), "你是 A 助手 {{input}}");
            Files.writeString(dirB.resolve("SKILL.md"), "你是 B 助手 {{input}}");

            ToolConfig cfgA = skillToolConfig("tool_a",
                    "{\"mode\":\"directory\",\"skillPath\":\"%s\",\"subModelId\":1}"
                            .formatted(dirA.toString().replace("\\", "\\\\")));
            ToolConfig cfgB = skillToolConfig("tool_b",
                    "{\"mode\":\"directory\",\"skillPath\":\"%s\",\"subModelId\":2}"
                            .formatted(dirB.toString().replace("\\", "\\\\")));

            engine.loadWindowTools(List.of(cfgA, cfgB));

            engine.execute(req("tool_a", "{\"input\":\"x\"}"));
            assertThat(captured.get().modelId()).isEqualTo(1L);
            assertThat(captured.get().systemPrompt()).isEqualTo("你是 A 助手 x");

            engine.execute(req("tool_b", "{\"input\":\"y\"}"));
            assertThat(captured.get().modelId()).isEqualTo(2L);
            assertThat(captured.get().systemPrompt()).isEqualTo("你是 B 助手 y");
        }
    }

    // ========== 2. 窗口默认模型继承（$windowDefaultModelId 注入链路） ==========

    @Nested
    @DisplayName("窗口默认模型继承")
    class WindowModelInheritance {

        @Test
        void subModelDefaultTrue_inheritsWindowDefaultModelId() throws IOException {
            Files.writeString(tempSkillDir.resolve("SKILL.md"),
                    """
                    ---
                    subModelDefault: true
                    ---
                    你好 {{input}}
                    """);

            ToolConfig cfg = skillToolConfig("greet",
                    "{\"mode\":\"directory\",\"skillPath\":\"%s\"}"
                            .formatted(tempSkillDir.toString().replace("\\", "\\\\")));

            // 窗口默认模型 42 → 引擎注入 $windowDefaultModelId → front matter subModelDefault 继承
            engine.loadWindowTools(List.of(cfg), 42L);
            engine.execute(req("greet", "{\"input\":\"小明\"}"));

            assertThat(captured.get().modelId()).isEqualTo(42L);
        }

        @Test
        void explicitSubModelId_overridesWindowDefault() throws IOException {
            Files.writeString(tempSkillDir.resolve("SKILL.md"), "prompt {{input}}");

            ToolConfig cfg = skillToolConfig("fixed_model",
                    "{\"mode\":\"directory\",\"skillPath\":\"%s\",\"subModelId\":7}"
                            .formatted(tempSkillDir.toString().replace("\\", "\\\\")));

            engine.loadWindowTools(List.of(cfg), 42L);   // 窗口默认 42，但显式 7 优先
            engine.execute(req("fixed_model", "{\"input\":\"x\"}"));

            assertThat(captured.get().modelId()).isEqualTo(7L);
        }

        @Test
        void noWindowDefault_noExplicitId_returnsErrorThroughEngine() throws IOException {
            Files.writeString(tempSkillDir.resolve("SKILL.md"), "prompt");

            ToolConfig cfg = skillToolConfig("no_model",
                    "{\"mode\":\"directory\",\"skillPath\":\"%s\"}"
                            .formatted(tempSkillDir.toString().replace("\\", "\\\\")));

            engine.loadWindowTools(List.of(cfg));   // 单参重载 → 无默认模型
            ToolExecutionResultMessage result = engine.execute(req("no_model", "{}"));

            assertThat(result.text()).contains("缺少子模型");
            assertThat(captured.get()).isNull();   // LLM 未被调用
        }

        @Test
        void differentWindows_differentDefaultModels() throws IOException {
            Files.writeString(tempSkillDir.resolve("SKILL.md"),
                    """
                    ---
                    subModelDefault: true
                    ---
                    hi
                    """);
            ToolConfig cfg = skillToolConfig("hi",
                    "{\"mode\":\"directory\",\"skillPath\":\"%s\"}"
                            .formatted(tempSkillDir.toString().replace("\\", "\\\\")));

            // 窗口 1：默认模型 10
            engine.loadWindowTools(List.of(cfg), 10L);
            engine.execute(req("hi", "{}"));
            assertThat(captured.get().modelId()).isEqualTo(10L);

            // 窗口 2 重新加载：默认模型 20（同线程模拟切换窗口）
            engine.loadWindowTools(List.of(cfg), 20L);
            engine.execute(req("hi", "{}"));
            assertThat(captured.get().modelId()).isEqualTo(20L);
        }
    }

    // ========== 3. 窗口隔离 + 注册过滤 ==========

    @Nested
    @DisplayName("窗口隔离与注册过滤")
    class WindowIsolation {

        @Test
        void unknownTool_returnsErrorListingRegisteredTools() {
            ToolConfig cfg = skillToolConfig("known_tool", "{\"mode\":\"inline\",\"promptTemplate\":\"x\",\"subModelId\":1}");
            engine.loadWindowTools(List.of(cfg));

            ToolExecutionResultMessage result = engine.execute(req("unknown_tool", "{}"));

            assertThat(result.text()).contains("not available");
            assertThat(result.text()).contains("known_tool");   // 错误消息里列出已注册工具
            assertThat(captured.get()).isNull();
        }

        @Test
        void disabledTool_notRegistered() {
            ToolConfig cfg = skillToolConfig("disabled_tool", "{\"mode\":\"inline\",\"promptTemplate\":\"x\",\"subModelId\":1}");
            cfg.setStatus(0);   // 禁用

            List<ToolSpecification> specs = engine.loadWindowTools(List.of(cfg));

            assertThat(specs).isEmpty();
            ToolExecutionResultMessage result = engine.execute(req("disabled_tool", "{}"));
            assertThat(result.text()).contains("not available");
        }

        @Test
        void clearWindowTools_resetsRegistration() {
            ToolConfig cfg = skillToolConfig("temp_tool", "{\"mode\":\"inline\",\"promptTemplate\":\"x\",\"subModelId\":1}");
            engine.loadWindowTools(List.of(cfg));
            assertThat(engine.execute(req("temp_tool", "{}")).text()).startsWith("LLM:");

            engine.clearWindowTools();

            ToolExecutionResultMessage result = engine.execute(req("temp_tool", "{}"));
            assertThat(result.text()).contains("not available");
        }

        @Test
        void reload_replacesOldToolSet() throws IOException {
            Path dirA = Files.createDirectory(tempSkillDir.resolve("only-a"));
            Files.writeString(dirA.resolve("SKILL.md"), "A prompt");
            ToolConfig cfgA = skillToolConfig("tool_a",
                    "{\"mode\":\"directory\",\"skillPath\":\"%s\",\"subModelId\":1}"
                            .formatted(dirA.toString().replace("\\", "\\\\")));

            engine.loadWindowTools(List.of(cfgA));

            // 重新加载一个不含 tool_a 的工具集 → 旧工具失效
            ToolConfig cfgB = skillToolConfig("tool_b", "{\"mode\":\"inline\",\"promptTemplate\":\"B\",\"subModelId\":1}");
            engine.loadWindowTools(List.of(cfgB));

            assertThat(engine.execute(req("tool_a", "{}")).text()).contains("not available");
            assertThat(engine.execute(req("tool_b", "{}")).text()).startsWith("LLM:");
        }
    }

    // ========== 4. definition 解析与 ToolSpecification ==========

    @Nested
    @DisplayName("definition 解析与 ToolSpecification 暴露")
    class DefinitionParsing {

        @Test
        void loadWindowTools_returnsSpecWithNameAndDescription() throws IOException {
            Files.writeString(tempSkillDir.resolve("SKILL.md"), "prompt");
            ToolConfig cfg = skillToolConfig("my_skill",
                    "{\"mode\":\"directory\",\"skillPath\":\"%s\",\"subModelId\":1}"
                            .formatted(tempSkillDir.toString().replace("\\", "\\\\")));

            List<ToolSpecification> specs = engine.loadWindowTools(List.of(cfg));

            assertThat(specs).hasSize(1);
            assertThat(specs.get(0).name()).isEqualTo("my_skill");
            assertThat(specs.get(0).description()).isEqualTo("my_skill 工具");
            assertThat(specs.get(0).parameters()).isNotNull();
        }

        @Test
        void invalidDefinitionJson_skippedButDoesNotBreakOthers() {
            ToolConfig bad = skillToolConfig("bad_tool", "{\"mode\":\"inline\",\"promptTemplate\":\"x\",\"subModelId\":1}");
            bad.setDefinition("这不是合法 JSON");

            ToolConfig good = skillToolConfig("good_tool", "{\"mode\":\"inline\",\"promptTemplate\":\"好 {{input}}\",\"subModelId\":1}");

            List<ToolSpecification> specs = engine.loadWindowTools(List.of(bad, good));

            // 坏的被跳过，好的正常注册
            assertThat(specs).hasSize(1);
            assertThat(specs.get(0).name()).isEqualTo("good_tool");

            ToolExecutionResultMessage result = engine.execute(req("good_tool", "{\"input\":\"ok\"}"));
            assertThat(result.text()).startsWith("LLM:");
        }

        @Test
        void invalidExecutorConfigJson_fallsBackToEmptyConfig() {
            ToolConfig cfg = skillToolConfig("broken_cfg", "这不是 JSON");
            engine.loadWindowTools(List.of(cfg));

            ToolExecutionResultMessage result = engine.execute(req("broken_cfg", "{}"));

            // executorConfig 解析失败 → 空 config → inline 模式缺 promptTemplate → 返回错误而非崩溃
            assertThat(result.text()).contains("Error");
        }
    }

    // ========== 5. 磁盘动态重载（通过引擎两次执行） ==========

    @Nested
    @DisplayName("磁盘动态重载")
    class DynamicReload {

        @Test
        void modifySkillFileBetweenExecutions_secondCallSeesNewContent() throws IOException {
            Path skillFile = tempSkillDir.resolve("SKILL.md");
            Files.writeString(skillFile, "版本1 prompt {{input}}");

            ToolConfig cfg = skillToolConfig("dynamic",
                    "{\"mode\":\"directory\",\"skillPath\":\"%s\",\"subModelId\":1}"
                            .formatted(tempSkillDir.toString().replace("\\", "\\\\")));
            engine.loadWindowTools(List.of(cfg));

            engine.execute(req("dynamic", "{\"input\":\"a\"}"));
            assertThat(captured.get().systemPrompt()).isEqualTo("版本1 prompt a");

            // 模拟用户在管理页面编辑并保存了 SKILL.md
            Files.writeString(skillFile, "版本2 prompt {{input}}");

            engine.execute(req("dynamic", "{\"input\":\"b\"}"));
            assertThat(captured.get().systemPrompt()).isEqualTo("版本2 prompt b");
        }

        @Test
        void deleteSkillDirBetweenExecutions_returnsLoadError() throws IOException {
            Path skillFile = tempSkillDir.resolve("SKILL.md");
            Files.writeString(skillFile, "存在的 prompt");

            ToolConfig cfg = skillToolConfig("vanishing",
                    "{\"mode\":\"directory\",\"skillPath\":\"%s\",\"subModelId\":1}"
                            .formatted(tempSkillDir.toString().replace("\\", "\\\\")));
            engine.loadWindowTools(List.of(cfg));

            assertThat(engine.execute(req("vanishing", "{}")).text()).startsWith("LLM:");

            // 删除整个目录
            Files.delete(skillFile);
            Files.delete(tempSkillDir);

            ToolExecutionResultMessage result = engine.execute(req("vanishing", "{}"));
            assertThat(result.text()).contains("目录不存在");
        }
    }

    // ========== 6. executeBatch ==========

    @Nested
    @DisplayName("批量执行")
    class BatchExecution {

        @Test
        void executeBatch_processesAllRequestsInOrder() throws IOException {
            Path dirA = Files.createDirectory(tempSkillDir.resolve("batch-a"));
            Files.writeString(dirA.resolve("SKILL.md"), "A {{input}}");

            ToolConfig cfgA = skillToolConfig("batch_a",
                    "{\"mode\":\"directory\",\"skillPath\":\"%s\",\"subModelId\":1}"
                            .formatted(dirA.toString().replace("\\", "\\\\")));
            ToolConfig cfgB = skillToolConfig("batch_b",
                    "{\"mode\":\"inline\",\"promptTemplate\":\"B {{input}}\",\"subModelId\":1}");

            engine.loadWindowTools(List.of(cfgA, cfgB));

            List<ToolExecutionResultMessage> results = engine.executeBatch(List.of(
                    req("batch_a", "{\"input\":\"1\"}"),
                    req("batch_b", "{\"input\":\"2\"}"),
                    req("missing", "{}")
            ));

            assertThat(results).hasSize(3);
            assertThat(results.get(0).text()).startsWith("LLM:");
            assertThat(results.get(1).text()).startsWith("LLM:");
            assertThat(results.get(2).text()).contains("not available");
        }
    }
}
