package com.wgh.rag.tool;

import com.wgh.rag.tool.definition.ToolDefinitionParser;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ToolDefinitionParser 单元测试 —— OpenAI function JSON → LangChain4j ToolSpecification
 */
@DisplayName("ToolDefinitionParser 测试")
class ToolDefinitionParserTest {

    // ============ 基本场景 ============

    @Nested
    @DisplayName("基本解析")
    class BasicParse {

        @Test
        @DisplayName("标准 OpenAI function 格式（type:function + function 包裹）")
        void parse_standardFunctionFormat() {
            String json = """
                    {
                      "type": "function",
                      "function": {
                        "name": "query_weather",
                        "description": "查询指定城市的实时天气，返回温度、天气状况、湿度",
                        "parameters": {
                          "type": "object",
                          "properties": {
                            "city": { "type": "string", "description": "城市名称，如 北京、上海" },
                            "units": { "type": "string", "enum": ["celsius", "fahrenheit"], "description": "温度单位" }
                          },
                          "required": ["city"]
                        }
                      }
                    }
                    """;

            ToolSpecification spec = ToolDefinitionParser.parse(json);

            assertThat(spec.name()).isEqualTo("query_weather");
            assertThat(spec.description()).isEqualTo("查询指定城市的实时天气，返回温度、天气状况、湿度");
            assertThat(spec.parameters()).isNotNull();
            assertThat(spec.parameters().properties()).containsKey("city");
            assertThat(spec.parameters().properties()).containsKey("units");
            assertThat(spec.parameters().required()).containsExactly("city");
        }

        @Test
        @DisplayName("直接 function JSON（无 type:function 包裹）")
        void parse_directFunctionNode() {
            String json = """
                    {
                      "name": "calc_discount",
                      "description": "计算折扣后价格",
                      "parameters": {
                        "type": "object",
                        "properties": {
                          "price": { "type": "number", "description": "原价" },
                          "discount": { "type": "number", "description": "折扣率 0-1" }
                        },
                        "required": ["price", "discount"]
                      }
                    }
                    """;

            ToolSpecification spec = ToolDefinitionParser.parse(json);

            assertThat(spec.name()).isEqualTo("calc_discount");
            assertThat(spec.parameters().required()).containsExactly("price", "discount");
        }

        @Test
        @DisplayName("无 parameters 的工具（不需要参数）")
        void parse_noParameters() {
            String json = """
                    {
                      "type": "function",
                      "function": {
                        "name": "get_current_time",
                        "description": "获取当前系统时间"
                      }
                    }
                    """;

            ToolSpecification spec = ToolDefinitionParser.parse(json);

            assertThat(spec.name()).isEqualTo("get_current_time");
            assertThat(spec.parameters()).isNull();
        }
    }

    // ============ 参数类型映射 ============

    @Nested
    @DisplayName("参数类型映射")
    class TypeMapping {

        @Test
        @DisplayName("integer / number / boolean / string 全类型映射")
        void parse_allJsonTypes() {
            String json = """
                    {
                      "type": "function",
                      "function": {
                        "name": "test_types",
                        "description": "测试所有类型",
                        "parameters": {
                          "type": "object",
                          "properties": {
                            "age": { "type": "integer", "description": "年龄" },
                            "price": { "type": "number", "description": "价格" },
                            "vip": { "type": "boolean", "description": "是否VIP" },
                            "name": { "type": "string", "description": "姓名" }
                          },
                          "required": ["age"]
                        }
                      }
                    }
                    """;

            ToolSpecification spec = ToolDefinitionParser.parse(json);

            assertThat(spec.parameters().properties()).hasSize(4);
            assertThat(spec.parameters().required()).containsExactly("age");
        }

        @Test
        @DisplayName("enum 类型特殊处理")
        void parse_enumType() {
            String json = """
                    {
                      "type": "function",
                      "function": {
                        "name": "set_mode",
                        "description": "设置模式",
                        "parameters": {
                          "type": "object",
                          "properties": {
                            "mode": {
                              "type": "string",
                              "enum": ["auto", "manual", "eco"],
                              "description": "运行模式"
                            }
                          },
                          "required": ["mode"]
                        }
                      }
                    }
                    """;

            ToolSpecification spec = ToolDefinitionParser.parse(json);

            assertThat(spec.parameters().properties()).containsKey("mode");
        }

        @Test
        @DisplayName("未识别的 type 默认降级为 string")
        void parse_unknownType_defaultsToString() {
            String json = """
                    {
                      "type": "function",
                      "function": {
                        "name": "weird",
                        "description": "奇怪类型",
                        "parameters": {
                          "type": "object",
                          "properties": {
                            "data": { "type": "unknown_type", "description": "未知" }
                          }
                        }
                      }
                    }
                    """;

            ToolSpecification spec = ToolDefinitionParser.parse(json);

            assertThat(spec.parameters().properties()).containsKey("data");
        }
    }

    // ============ 异常场景 ============

    @Nested
    @DisplayName("异常处理")
    class ExceptionHandling {

        @Test
        @DisplayName("JSON 格式错误 → 抛 IllegalArgumentException")
        void parse_invalidJson_throws() {
            assertThatThrownBy(() -> ToolDefinitionParser.parse("not-json"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("null / 空字符串 → 抛 IllegalArgumentException")
        void parse_nullOrBlankJson_throws() {
            assertThatThrownBy(() -> ToolDefinitionParser.parse(null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> ToolDefinitionParser.parse(""))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> ToolDefinitionParser.parse("   "))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ============ extractFunctionNode ============

    @Nested
    @DisplayName("extractFunctionNode 工具方法")
    class ExtractFunctionNode {

        @Test
        @DisplayName("正确提取 function 节点")
        void extractFunctionNode_ok() {
            String json = """
                    {
                      "type": "function",
                      "function": { "name": "foo", "description": "bar" }
                    }
                    """;

            var fn = ToolDefinitionParser.extractFunctionNode(json);
            assertThat(fn.get("name")).isEqualTo("foo");
            assertThat(fn.get("description")).isEqualTo("bar");
        }
    }
}
