package com.wgh.rag.tool.definition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * 工具定义解析器 —— 把 ToolConfig.definition（OpenAI function JSON 格式）
 * 转换为 LangChain4j 1.19.0+ 的 ToolSpecification
 *
 * 输入 JSON 格式示例：
 * <pre>
 * {
 *   "type": "function",
 *   "function": {
 *     "name": "query_weather",
 *     "description": "查询指定城市的实时天气",
 *     "parameters": {
 *       "type": "object",
 *       "properties": {
 *         "city": { "type": "string", "description": "城市名称" },
 *         "units": { "type": "string", "enum": ["celsius", "fahrenheit"] }
 *       },
 *       "required": ["city"]
 *     }
 *   }
 * }
 * </pre>
 */
@Slf4j
public class ToolDefinitionParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 从 JSON 字符串解析 ToolSpecification
     */
    public static ToolSpecification parse(String definitionJson) {
        try {
            if (definitionJson == null || definitionJson.isBlank()) {
                throw new IllegalArgumentException("工具 definition 不能为空");
            }
            JsonNode root = MAPPER.readTree(definitionJson);
            JsonNode function = root.has("function") ? root.get("function") : root;

            String name = function.path("name").asText();
            String description = function.path("description").asText("");

            ToolSpecification.Builder builder = ToolSpecification.builder()
                    .name(name)
                    .description(description);

            JsonNode parameters = function.path("parameters");
            if (parameters.isMissingNode() || parameters.isNull()) {
                return builder.build();
            }

            // 构造 JsonObjectSchema
            JsonObjectSchema.Builder paramBuilder = JsonObjectSchema.builder();

            JsonNode properties = parameters.path("properties");
            Set<String> required = new LinkedHashSet<>();
            parameters.path("required").forEach(n -> required.add(n.asText()));

            properties.fields().forEachRemaining(entry -> {
                String paramName = entry.getKey();
                JsonNode schema = entry.getValue();
                String desc = schema.path("description").asText("");
                String type = schema.path("type").asText("string");

                JsonNode enumNode = schema.path("enum");
                if (enumNode.isArray()) {
                    // enum 类型
                    List<String> enumValues = new ArrayList<>();
                    enumNode.forEach(v -> enumValues.add(v.asText()));
                    paramBuilder.addEnumProperty(paramName, enumValues, desc);
                } else {
                    switch (type.toLowerCase()) {
                        case "integer" -> paramBuilder.addIntegerProperty(paramName, desc);
                        case "number" -> paramBuilder.addNumberProperty(paramName, desc);
                        case "boolean" -> paramBuilder.addBooleanProperty(paramName, desc);
                        default -> paramBuilder.addStringProperty(paramName, desc);
                    }
                }
            });

            if (!required.isEmpty()) {
                paramBuilder.required(new ArrayList<>(required));
            }

            builder.parameters(paramBuilder.build());
            return builder.build();

        } catch (Exception e) {
            log.error("解析 Tool definition 失败: {}", definitionJson, e);
            throw new IllegalArgumentException("工具 definition JSON 格式错误: " + e.getMessage(), e);
        }
    }

    /**
     * 从 definition JSON 里提取 function 节点 Map（供调试或前端展示）
     */
    public static Map<String, Object> extractFunctionNode(String definitionJson) {
        try {
            JsonNode root = MAPPER.readTree(definitionJson);
            JsonNode fn = root.has("function") ? root.get("function") : root;
            return MAPPER.convertValue(fn, Map.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("工具 definition JSON 格式错误", e);
        }
    }
}
