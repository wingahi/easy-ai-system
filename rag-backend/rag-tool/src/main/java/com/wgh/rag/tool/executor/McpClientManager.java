package com.wgh.rag.tool.executor;

import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP Client 缓存管理器 —— 按 serverUrl 复用 McpClient 实例，避免每次工具调用都新建连接
 * <p>
 * McpClient 本身是 AutoCloseable，但长期运行的 Spring Boot 应用里连接应该保持存活复用。
 * 如果 serverUrl 重复配置（多个 ToolConfig 指向同一个 MCP server），自动复用同一个 McpClient。
 *
 * <h3>executorConfig schema：</h3>
 * <pre>
 * {
 *   "serverUrl": "http://mcp-server.local:8000/mcp",  // Streamable HTTP 传输的端点
 *   "transport": "http",                                // "http" (默认) | "stdio"
 *   "command": "npx",                                   // stdio 模式：子进程启动命令
 *   "args": ["-y", "@modelcontextprotocol/server-filesystem"]  // stdio 参数
 *   "timeoutMs": 10000                                  // 超时（默认 10000）
 * }
 * </pre>
 */
@Slf4j
@Component
public class McpClientManager {

    /** serverUrl → McpClient 实例缓存（ConcurrentHashMap 保证线程安全） */
    private final Map<String, McpClient> clientCache = new ConcurrentHashMap<>();

    /**
     * 获取或创建 McpClient —— 相同 serverUrl 复用同一实例
     */
    public McpClient getOrCreateClient(Map<String, Object> executorConfig) {
        String transport = (String) executorConfig.getOrDefault("transport", "http");

        if ("stdio".equalsIgnoreCase(transport)) {
            return getOrCreateStdioClient(executorConfig);
        } else {
            String serverUrl = (String) executorConfig.get("serverUrl");
            return getOrCreateHttpClient(serverUrl, executorConfig);
        }
    }

    /**
     * 移除并关闭指定 serverUrl 的 McpClient —— server 地址变更时调用
     */
    public void evictClient(String key) {
        McpClient client = clientCache.remove(key);
        if (client != null) {
            try {
                client.close();
                log.info("关闭 MCP Client: {}", key);
            } catch (Exception e) {
                log.warn("关闭 MCP Client 失败: {}", key, e);
            }
        }
    }

    /** 关闭所有缓存的 client（应用关闭时调用） */
    public void shutdown() {
        clientCache.forEach((k, client) -> {
            try { client.close(); } catch (Exception e) { log.warn("关闭 MCP Client 失败", e); }
        });
        clientCache.clear();
    }

    // ============ 内部 ============

    private McpClient getOrCreateHttpClient(String serverUrl, Map<String, Object> config) {
        if (serverUrl == null || serverUrl.isBlank()) {
            throw new IllegalArgumentException("MCP serverUrl 不能为空");
        }
        return clientCache.computeIfAbsent(serverUrl, url -> {
            log.info("创建 MCP Client (HTTP): {}", url);
            McpTransport transport = StreamableHttpMcpTransport.builder()
                    .url(url)
                    .logRequests(false)
                    .logResponses(false)
                    .build();
            Duration timeout = parseTimeout(config.get("timeoutMs"));
            return DefaultMcpClient.builder()
                    .key("rag-mcp-" + sanitizeKey(url))
                    .transport(transport)
                    .autoHealthCheck(false)
                    .toolExecutionTimeout(timeout)
                    .initializationTimeout(timeout)
                    .build();
        });
    }

    private McpClient getOrCreateStdioClient(Map<String, Object> config) {
        String command = (String) config.get("command");
        @SuppressWarnings("unchecked")
        List<String> args = config.get("args") instanceof List
                ? (List<String>) config.get("args")
                : List.of();
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("MCP stdio 模式 executorConfig.command 不能为空");
        }
        String key = "stdio:" + command + (args.isEmpty() ? "" : ":" + String.join(" ", args));
        return clientCache.computeIfAbsent(key, k -> {
            log.info("创建 MCP Client (stdio): {} {}", command, args);
            McpTransport transport = StdioMcpTransport.builder()
                    .command(java.util.stream.Stream.concat(
                            java.util.stream.Stream.of(command), args.stream()
                    ).toList())
                    .logEvents(false)
                    .build();
            Duration timeout = parseTimeout(config.get("timeoutMs"));
            return DefaultMcpClient.builder()
                    .key("rag-mcp-" + sanitizeKey(key))
                    .transport(transport)
                    .autoHealthCheck(false)
                    .toolExecutionTimeout(timeout)
                    .initializationTimeout(timeout)
                    .build();
        });
    }

    private Duration parseTimeout(Object o) {
        long ms = 10000; // 默认 10 秒
        if (o instanceof Number) ms = ((Number) o).longValue();
        else if (o != null) {
            try { ms = Long.parseLong(o.toString()); } catch (Exception ignored) {}
        }
        if (ms < 1000) ms = 1000; // 最小 1 秒
        return Duration.ofMillis(ms);
    }

    private String sanitizeKey(String key) {
        return key.replaceAll("[^a-zA-Z0-9-_]", "-");
    }
}
