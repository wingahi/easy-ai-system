package com.wgh.rag.tool.executor;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.mcp.client.McpClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * MCP (Model Context Protocol) 工具执行器 —— 真实对接 LangChain4j MCP 客户端
 *
 * <h3>executorConfig schema：</h3>
 * <pre>
 * {
 *   "serverUrl": "http://mcp-server.local:8000/mcp", // HTTP 传输端点
 *   "transport": "http",                               // "http" (默认) | "stdio"
 *   "command": "npx",                                  // stdio 模式：子进程命令
 *   "args": ["-y", "@modelcontextprotocol/server-filesystem"]
 *   "timeoutMs": 10000                                 // 超时（默认 10s）
 * }
 * </pre>
 *
 * 底层：McpClientManager 按 serverUrl 缓存 McpClient（StreamableHttpMcpTransport / StdioMcpTransport），
 *      相同 serverUrl 复用同一连接，避免每次工具调用都建 TCP 连接。
 *
 * 支持传输：Streamable HTTP / stdio（LangChain4j 1.19 弃用旧 HTTP+SSE）
 */
@Slf4j
@Component
public class McpToolExecutor implements ToolExecutor {

    private final McpClientManager mcpClientManager;

    public McpToolExecutor(McpClientManager mcpClientManager) {
        this.mcpClientManager = mcpClientManager;
    }

    @Override
    public String supportedToolType() {
        return "MCP";
    }

    @Override
    public ToolExecutionResultMessage execute(Map<String, Object> executorConfig, ToolExecutionRequest request) {
        // 字段校验
        String transport = (String) executorConfig.getOrDefault("transport", "http");
        String serverUrl = (String) executorConfig.get("serverUrl");

        if ("http".equalsIgnoreCase(transport) || transport == null) {
            if (serverUrl == null || serverUrl.isBlank()) {
                return error(request, "Error: MCP executorConfig 缺少 serverUrl（http 传输）");
            }
        } else if ("stdio".equalsIgnoreCase(transport)) {
            if (!executorConfig.containsKey("command")) {
                return error(request, "Error: MCP executorConfig 缺少 command（stdio 传输）");
            }
        } else {
            return error(request, "Error: MCP executorConfig.transport 必须是 'http' 或 'stdio'");
        }

        try {
            McpClient client = mcpClientManager.getOrCreateClient(executorConfig);
            log.info("MCP 工具调用: server={}, tool={}, args={}", serverUrl, request.name(), request.arguments());

            // McpClient.executeTool 返回 ToolExecutionResult，需转成 ToolExecutionResultMessage
            var mcpResult = client.executeTool(request);
            String text = mcpResult.isError()
                    ? "Error: " + mcpResult.resultText()
                    : mcpResult.resultText();
            log.info("MCP 工具结果: tool={}, text={} chars, isError={}", request.name(), text.length(), mcpResult.isError());
            return new ToolExecutionResultMessage(request.id(), request.name(), text);
        } catch (Exception e) {
            log.error("MCP 工具调用失败: server={}, tool={}", serverUrl, request.name(), e);
            return error(request, "Error: MCP 工具调用失败 - " + e.getMessage());
        }
    }

    private ToolExecutionResultMessage error(ToolExecutionRequest request, String msg) {
        return new ToolExecutionResultMessage(request.id(), request.name(), msg);
    }
}
