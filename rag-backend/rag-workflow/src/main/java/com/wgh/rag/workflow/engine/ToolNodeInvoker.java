package com.wgh.rag.workflow.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.tool.definition.ToolDefinitionParser;
import com.wgh.rag.tool.entity.ToolConfig;
import com.wgh.rag.tool.executor.ToolExecutionEngine;
import com.wgh.rag.tool.service.ToolConfigService;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 按 toolId 直接调用工具 —— 供工作流 TOOL 节点使用。
 * 复用 ToolExecutionEngine 的统一分发能力，不经过 LLM 决策。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolNodeInvoker {

    private final ToolConfigService toolConfigService;
    private final ToolExecutionEngine toolExecutionEngine;
    private final ObjectMapper objectMapper;

    /**
     * 安全模式校验：分享态运行禁止有写操作副作用的工具。
     * HTTP 仅允许 GET / HEAD；MCP 无法保证只读，直接禁止。其他类型（SKILL / FUNCTION_CALLING）不受限。
     */
    public void assertSafeMode(Long toolId) {
        ToolConfig tool = toolConfigService.getById(toolId);
        if (tool == null || tool.getToolType() == null) return;
        String type = tool.getToolType().toUpperCase();
        if ("MCP".equals(type)) {
            throw new IllegalStateException("分享模式下禁止执行 MCP 工具：" + tool.getName());
        }
        if ("HTTP".equals(type)) {
            String method = "GET";
            try {
                if (tool.getExecutorConfig() != null && !tool.getExecutorConfig().isBlank()) {
                    Map<String, Object> cfg = objectMapper.readValue(
                            tool.getExecutorConfig(), new TypeReference<Map<String, Object>>() {});
                    method = String.valueOf(cfg.getOrDefault("method", "GET")).toUpperCase();
                }
            } catch (Exception e) {
                log.warn("解析 HTTP 工具 method 失败，按 GET 处理: toolId={}", toolId, e);
            }
            if (!"GET".equals(method) && !"HEAD".equals(method)) {
                throw new IllegalStateException(
                        "分享模式下禁止执行写操作 HTTP 工具：" + tool.getName() + " (" + method + ")");
            }
        }
    }

    /**
     * @return 工具返回文本；异常或错误返回以 "Error:" 开头
     */
    public String invoke(Long toolId, Map<String, Object> args) {
        ToolConfig tool = toolConfigService.getById(toolId);
        String toolName = resolveName(tool);
        try {
            String arguments = objectMapper.writeValueAsString(args != null ? args : Map.of());
            ToolExecutionRequest request = ToolExecutionRequest.builder()
                    .id("wf-" + System.currentTimeMillis())
                    .name(toolName)
                    .arguments(arguments)
                    .build();
            ToolExecutionResultMessage result =
                    toolExecutionEngine.execute(request, Map.of(toolName, tool), null);
            return result != null ? result.text() : "";
        } catch (Exception e) {
            log.error("工作流工具节点执行失败: toolId={}", toolId, e);
            return "Error: " + e.getMessage();
        }
    }

    private String resolveName(ToolConfig tool) {
        try {
            Object name = ToolDefinitionParser.extractFunctionNode(tool.getDefinition()).get("name");
            if (name != null && !String.valueOf(name).isBlank()) return String.valueOf(name);
        } catch (Exception ignored) {
            // definition 不完整时回退到工具名
        }
        return tool.getName();
    }
}
