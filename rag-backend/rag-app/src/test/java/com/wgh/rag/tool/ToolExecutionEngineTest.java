package com.wgh.rag.tool;

import com.wgh.rag.tool.entity.ToolConfig;
import com.wgh.rag.tool.executor.ToolExecutionEngine;
import com.wgh.rag.tool.executor.ToolExecutor;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ToolExecutionEngine 单元测试 —— 核心编排 + 窗口隔离
 */
@DisplayName("ToolExecutionEngine 测试")
class ToolExecutionEngineTest {

    // 两个 mock executor，分别支持 HTTP 和 SKILL
    private ToolExecutor httpExecutor;
    private ToolExecutor skillExecutor;
    private ToolExecutionEngine engine;

    @BeforeEach
    void setUp() {
        httpExecutor = new ToolExecutor() {
            @Override
            public String supportedToolType() { return "HTTP"; }
            @Override
            public ToolExecutionResultMessage execute(Map<String, Object> config, ToolExecutionRequest request) {
                return new ToolExecutionResultMessage(request.id(), request.name(),
                        "[HTTP] executed " + request.name() + " args=" + request.arguments());
            }
        };

        skillExecutor = new ToolExecutor() {
            @Override
            public String supportedToolType() { return "SKILL"; }
            @Override
            public ToolExecutionResultMessage execute(Map<String, Object> config, ToolExecutionRequest request) {
                return new ToolExecutionResultMessage(request.id(), request.name(),
                        "[SKILL] executed " + request.name());
            }
        };

        engine = new ToolExecutionEngine(List.of(httpExecutor, skillExecutor));
    }

    @AfterEach
    void tearDown() {
        engine.clearWindowTools();
    }

    // ============ 构造 ToolConfig 帮助方法 ============

    private ToolConfig buildToolConfig(Long id, String name, String toolType, String definition, String executorConfig) {
        ToolConfig cfg = new ToolConfig();
        cfg.setId(id);
        cfg.setName(name);
        cfg.setToolType(toolType);
        cfg.setDefinition(definition);
        cfg.setExecutorConfig(executorConfig);
        cfg.setStatus(1);
        return cfg;
    }

    private String definition(String toolName, String... requiredParams) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"function\",\"function\":{\"name\":\"").append(toolName)
                .append("\",\"description\":\"测试工具\",\"parameters\":{\"type\":\"object\",\"properties\":{");
        for (int i = 0; i < requiredParams.length; i++) {
            sb.append("\"").append(requiredParams[i]).append("\":{\"type\":\"string\",\"description\":\"p").append(i).append("\"},");
        }
        if (requiredParams.length > 0) sb.setLength(sb.length() - 1); // 去掉末尾逗号
        sb.append("}");
        if (requiredParams.length > 0) {
            sb.append(",\"required\":[");
            for (int i = 0; i < requiredParams.length; i++) {
                sb.append("\"").append(requiredParams[i]).append("\",");
            }
            sb.setLength(sb.length() - 1);
            sb.append("]");
        }
        sb.append("}}}");
        return sb.toString();
    }

    // ============ loadWindowTools ============

    @Nested
    @DisplayName("loadWindowTools — 窗口级工具注册")
    class LoadWindowTools {

        /** 正常加载工具后返回包含工具名的 ToolSpecification 列表 */
        @Test
        @DisplayName("正常加载 → 返回 ToolSpecification 列表")
        void loadWindowTools_ok() {
            ToolConfig weather = buildToolConfig(1L, "query_weather", "HTTP",
                    definition("query_weather", "city"),
                    """
                            {"url":"http://mock/weather"}
                            """);

            List<ToolSpecification> specs = engine.loadWindowTools(List.of(weather));

            assertThat(specs).hasSize(1);
            assertThat(specs.get(0).name()).isEqualTo("query_weather");
        }

        /** status 不为 1 的工具在加载时被过滤掉 */
        @Test
        @DisplayName("status != 1 的工具被过滤")
        void loadWindowTools_filterDisabled() {
            ToolConfig disabled = buildToolConfig(2L, "disabled_tool", "HTTP",
                    definition("disabled_tool"), "{}");
            disabled.setStatus(0);

            List<ToolSpecification> specs = engine.loadWindowTools(List.of(disabled));

            assertThat(specs).isEmpty();
        }

        /** definition 为非法 JSON 时跳过该工具且不抛异常 */
        @Test
        @DisplayName("definition JSON 无效 → 跳过不崩溃")
        void loadWindowTools_invalidDefinition_skips() {
            ToolConfig bad = buildToolConfig(3L, "bad_tool", "HTTP",
                    "not-valid-json", "{}");

            List<ToolSpecification> specs = engine.loadWindowTools(List.of(bad));

            assertThat(specs).isEmpty();
        }

        /** 传入空工具列表时返回空的 spec 列表 */
        @Test
        @DisplayName("空列表 → 返回空 spec")
        void loadWindowTools_empty() {
            List<ToolSpecification> specs = engine.loadWindowTools(List.of());
            assertThat(specs).isEmpty();
        }
    }

    // ============ execute ============

    @Nested
    @DisplayName("execute — 分发到正确的 ToolExecutor")
    class Execute {

        /** HTTP 类型工具分发到对应 executor 并带回请求参数 */
        @Test
        @DisplayName("HTTP 类型 → 分发到 HttpToolExecutor")
        void execute_http_dispatchedCorrectly() {
            ToolConfig weather = buildToolConfig(1L, "query_weather", "HTTP",
                    definition("query_weather", "city"), "{}");
            engine.loadWindowTools(List.of(weather));

            ToolExecutionResultMessage result = engine.execute(
                    ToolExecutionRequest.builder().id("c1").name("query_weather").arguments("""
                            {"city":"Beijing"}
                            """).build());

            assertThat(result.text()).contains("[HTTP]");
            assertThat(result.text()).contains("Beijing");
        }

        /** SKILL 类型工具分发到对应 executor */
        @Test
        @DisplayName("SKILL 类型 → 分发到 SkillToolExecutor")
        void execute_skill_dispatchedCorrectly() {
            ToolConfig skill = buildToolConfig(2L, "skill_foo", "SKILL",
                    definition("skill_foo"), "{}");
            engine.loadWindowTools(List.of(skill));

            ToolExecutionResultMessage result = engine.execute(
                    ToolExecutionRequest.builder().id("c2").name("skill_foo").arguments("{}").build());

            assertThat(result.text()).contains("[SKILL]");
        }

        /** 未加载任何工具时执行返回以 Error: 开头并包含工具名的结果 */
        @Test
        @DisplayName("未知工具 → result 以 Error 开头")
        void execute_unknownTool_returnsError() {
            // 不 loadWindowTools，直接 execute
            ToolExecutionResultMessage result = engine.execute(
                    ToolExecutionRequest.builder().id("c3").name("ghost_tool").arguments("{}").build());

            assertThat(result.text()).startsWith("Error:");
            assertThat(result.text()).contains("ghost_tool");
        }

        /** 找不到对应 toolType 的 ToolExecutor 时抛出 IllegalStateException */
        @Test
        @DisplayName("找不到对应 toolType 的 ToolExecutor → 抛 IllegalStateException")
        void execute_missingExecutor_throws() {
            ToolConfig mcpTool = buildToolConfig(3L, "mcp_foo", "MCP",
                    definition("mcp_foo"), "{}");
            engine.loadWindowTools(List.of(mcpTool));

            // MCP 类型没注册 mock executor
            assertThatThrownBy(() -> engine.execute(
                    ToolExecutionRequest.builder().id("c4").name("mcp_foo").arguments("{}").build()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("MCP");
        }
    }

    // ============ executeBatch ============

    @Nested
    @DisplayName("executeBatch — 批量执行")
    class ExecuteBatch {

        /** 批量执行多个 tool call 并按顺序返回各自结果 */
        @Test
        @DisplayName("多个 tool calls 全部执行")
        void executeBatch_allExecuted() {
            ToolConfig t1 = buildToolConfig(1L, "tool_a", "HTTP", definition("tool_a"), "{}");
            ToolConfig t2 = buildToolConfig(2L, "tool_b", "HTTP", definition("tool_b"), "{}");
            engine.loadWindowTools(List.of(t1, t2));

            var results = engine.executeBatch(List.of(
                    ToolExecutionRequest.builder().id("c1").name("tool_a").arguments("{}").build(),
                    ToolExecutionRequest.builder().id("c2").name("tool_b").arguments("{}").build()
            ));

            assertThat(results).hasSize(2);
            assertThat(results.get(0).text()).contains("tool_a");
            assertThat(results.get(1).text()).contains("tool_b");
        }
    }

    // ============ 窗口隔离 ============

    @Nested
    @DisplayName("窗口隔离 — ThreadLocal 每次 loadWindowTools 重置")
    class WindowIsolation {

        /** 同一线程重新加载工具后只有新工具可用，旧工具不可用 */
        @Test
        @DisplayName("窗口 A 加载 weather，窗口 B 加载 skill → A 无法调用 skill")
        void windowIsolation_threadLocalResets() {
            ToolConfig weather = buildToolConfig(1L, "query_weather", "HTTP",
                    definition("query_weather"), "{}");
            ToolConfig skill = buildToolConfig(2L, "skill_foo", "SKILL",
                    definition("skill_foo"), "{}");

            // 模拟窗口 A
            engine.loadWindowTools(List.of(weather));
            // 模拟窗口 B 覆盖（同一线程）
            engine.loadWindowTools(List.of(skill));

            // 现在应该只有 skill，weather 被覆盖
            var r1 = engine.execute(
                    ToolExecutionRequest.builder().id("c1").name("skill_foo").arguments("{}").build());
            assertThat(r1.text()).contains("[SKILL]");

            // weather 不可用
            var r2 = engine.execute(
                    ToolExecutionRequest.builder().id("c2").name("query_weather").arguments("{}").build());
            assertThat(r2.text()).startsWith("Error:");
        }

        /** 清空窗口工具后所有工具都不可用 */
        @Test
        @DisplayName("clearWindowTools 后 → 所有工具都不可用")
        void clearWindowTools_resetsEverything() {
            ToolConfig t = buildToolConfig(1L, "t", "HTTP", definition("t"), "{}");
            engine.loadWindowTools(List.of(t));

            engine.clearWindowTools();

            var r = engine.execute(
                    ToolExecutionRequest.builder().id("c1").name("t").arguments("{}").build());
            assertThat(r.text()).startsWith("Error:");
        }
    }

    // ============ executorConfig JSON 解析 ============

    @Nested
    @DisplayName("executorConfig 解析")
    class ExecutorConfigParsing {

        /** executorConfig 为 null 时当作空 map 处理并正常分发 */
        @Test
        @DisplayName("null executorConfig → 当作空 map 不崩溃")
        void execute_nullExecutorConfig_ok() {
            ToolConfig cfg = buildToolConfig(1L, "no_config", "HTTP",
                    definition("no_config"), null);
            engine.loadWindowTools(List.of(cfg));

            var result = engine.execute(
                    ToolExecutionRequest.builder().id("c1").name("no_config").arguments("{}").build());
            // mock httpExecutor 不关心 config 内容，只要被分发到就行
            assertThat(result.text()).contains("[HTTP]");
        }

        /** executorConfig 为非 JSON 字符串时当作空 map 处理并正常分发 */
        @Test
        @DisplayName("executorConfig 非 JSON → 当作空 map")
        void execute_badExecutorConfig_ok() {
            ToolConfig cfg = buildToolConfig(1L, "bad_config", "HTTP",
                    definition("bad_config"), "not-json");
            engine.loadWindowTools(List.of(cfg));

            var result = engine.execute(
                    ToolExecutionRequest.builder().id("c1").name("bad_config").arguments("{}").build());
            assertThat(result.text()).contains("[HTTP]");
        }
    }
}
