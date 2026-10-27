package com.wgh.rag.tool;

import com.wgh.rag.tool.executor.McpClientManager;
import com.wgh.rag.tool.executor.McpToolExecutor;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.service.tool.ToolExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * McpToolExecutor 单元测试 —— 字段校验 + 真实 McpClient 调用 + 错误处理
 */
@DisplayName("McpToolExecutor")
class McpToolExecutorTest {

    private McpClientManager mockManager;
    private McpToolExecutor executor;

    @BeforeEach
    void setup() {
        mockManager = mock(McpClientManager.class);
        executor = new McpToolExecutor(mockManager);
    }

    @Test
    void supportedToolType_returnsMCP() {
        assertThat(executor.supportedToolType()).isEqualTo("MCP");
    }

    // ============ executorConfig 字段校验 ============

    @Nested
    @DisplayName("executorConfig 字段校验")
    class ConfigValidation {

        @Test
        void missingServerUrl_http_returnsError() {
            Map<String, Object> config = Map.of("transport", "http");
            ToolExecutionRequest req = ToolExecutionRequest.builder()
                    .id("t1").name("mcp_tool").arguments("{}").build();

            ToolExecutionResultMessage result = executor.execute(config, req);

            assertThat(result.text()).contains("serverUrl");
            verifyNoInteractions(mockManager);
        }

        @Test
        void blankServerUrl_http_returnsError() {
            Map<String, Object> config = Map.of("serverUrl", "   ", "transport", "http");
            ToolExecutionRequest req = ToolExecutionRequest.builder()
                    .id("t1").name("mcp_tool").arguments("{}").build();

            ToolExecutionResultMessage result = executor.execute(config, req);

            assertThat(result.text()).contains("serverUrl");
            verifyNoInteractions(mockManager);
        }

        @Test
        void missingCommand_stdio_returnsError() {
            Map<String, Object> config = Map.of("transport", "stdio");
            ToolExecutionRequest req = ToolExecutionRequest.builder()
                    .id("t1").name("mcp_tool").arguments("{}").build();

            ToolExecutionResultMessage result = executor.execute(config, req);

            assertThat(result.text()).contains("command");
            verifyNoInteractions(mockManager);
        }

        @Test
        void invalidTransport_returnsError() {
            Map<String, Object> config = Map.of("serverUrl", "http://mcp.local", "transport", "grpc");
            ToolExecutionRequest req = ToolExecutionRequest.builder()
                    .id("t1").name("mcp_tool").arguments("{}").build();

            ToolExecutionResultMessage result = executor.execute(config, req);

            assertThat(result.text()).contains("transport");
            verifyNoInteractions(mockManager);
        }
    }

    // ============ 真实 McpClient 调用 ============

    @Nested
    @DisplayName("McpClient 调用 + 结果转换")
    class McpCall {

        @Test
        void httpTransport_callsMcpClientAndReturnsText() {
            McpClient mockClient = mock(McpClient.class);
            ToolExecutionResult mcpResult = ToolExecutionResult.builder()
                    .resultText("天气：晴 25°C")
                    .build();
            when(mockClient.executeTool(any(ToolExecutionRequest.class))).thenReturn(mcpResult);
            when(mockManager.getOrCreateClient(anyMap())).thenReturn(mockClient);

            Map<String, Object> config = Map.of(
                    "serverUrl", "http://mcp.local:8000/mcp",
                    "transport", "http"
            );
            ToolExecutionRequest req = ToolExecutionRequest.builder()
                    .id("t1").name("query_weather")
                    .arguments("{\"city\":\"北京\"}").build();

            ToolExecutionResultMessage result = executor.execute(config, req);

            assertThat(result.text()).isEqualTo("天气：晴 25°C");
            assertThat(result.toolName()).isEqualTo("query_weather");
            verify(mockManager).getOrCreateClient(config);
            verify(mockClient).executeTool(req);
        }

        @Test
        void stdioTransport_callsMcpClient() {
            McpClient mockClient = mock(McpClient.class);
            ToolExecutionResult mcpResult = ToolExecutionResult.builder()
                    .resultText("文件列表")
                    .build();
            when(mockClient.executeTool(any(ToolExecutionRequest.class))).thenReturn(mcpResult);
            when(mockManager.getOrCreateClient(anyMap())).thenReturn(mockClient);

            Map<String, Object> config = Map.of(
                    "transport", "stdio",
                    "command", "npx",
                    "args", java.util.List.of("-y", "@modelcontextprotocol/server-filesystem")
            );
            ToolExecutionRequest req = ToolExecutionRequest.builder()
                    .id("t1").name("list_files").arguments("{}").build();

            ToolExecutionResultMessage result = executor.execute(config, req);

            assertThat(result.text()).isEqualTo("文件列表");
            verify(mockManager).getOrCreateClient(config);
        }

        @Test
        void mcpResultError_returnsErrorMessage() {
            McpClient mockClient = mock(McpClient.class);
            ToolExecutionResult mcpResult = ToolExecutionResult.builder()
                    .resultText("找不到文件")
                    .isError(true)
                    .build();
            when(mockClient.executeTool(any(ToolExecutionRequest.class))).thenReturn(mcpResult);
            when(mockManager.getOrCreateClient(anyMap())).thenReturn(mockClient);

            Map<String, Object> config = Map.of("serverUrl", "http://mcp.local", "transport", "http");
            ToolExecutionRequest req = ToolExecutionRequest.builder()
                    .id("t1").name("read_file").arguments("{}").build();

            ToolExecutionResultMessage result = executor.execute(config, req);

            assertThat(result.text()).contains("Error:");
            assertThat(result.text()).contains("找不到文件");
        }

        @Test
        void mcpClientThrowsException_wrapsInError() {
            when(mockManager.getOrCreateClient(anyMap()))
                    .thenThrow(new RuntimeException("连接超时 http://mcp.local"));

            Map<String, Object> config = Map.of("serverUrl", "http://mcp.local", "transport", "http");
            ToolExecutionRequest req = ToolExecutionRequest.builder()
                    .id("t1").name("foo").arguments("{}").build();

            ToolExecutionResultMessage result = executor.execute(config, req);

            assertThat(result.text()).contains("Error:");
            assertThat(result.text()).contains("连接超时");
        }

        @Test
        void defaultTransportIsHttp_whenOmitted() {
            McpClient mockClient = mock(McpClient.class);
            when(mockClient.executeTool(any(ToolExecutionRequest.class)))
                    .thenReturn(ToolExecutionResult.builder().resultText("ok").build());
            when(mockManager.getOrCreateClient(anyMap())).thenReturn(mockClient);

            Map<String, Object> config = Map.of("serverUrl", "http://mcp.local");
            ToolExecutionRequest req = ToolExecutionRequest.builder()
                    .id("t1").name("foo").arguments("{}").build();

            ToolExecutionResultMessage result = executor.execute(config, req);

            assertThat(result.text()).isEqualTo("ok");
            verify(mockManager).getOrCreateClient(anyMap());
        }
    }
}
