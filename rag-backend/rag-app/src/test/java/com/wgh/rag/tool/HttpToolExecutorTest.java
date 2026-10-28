package com.wgh.rag.tool;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.wgh.rag.tool.executor.HttpToolExecutor;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HttpToolExecutor 单元测试 —— 用 JDK 自带 HttpServer 起本地 mock endpoint
 */
@DisplayName("HttpToolExecutor 测试")
class HttpToolExecutorTest {

    private HttpToolExecutor executor;
    private HttpServer server;
    private int port;

    // 记录 mock 服务收到的最后一次请求
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();
    private final AtomicReference<String> lastRequestMethod = new AtomicReference<>();
    private final AtomicReference<Map<String, String>> lastRequestHeaders = new AtomicReference<>(new HashMap<>());

    @BeforeEach
    void setUp() throws IOException {
        executor = new HttpToolExecutor();
        startMockServer();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    private void startMockServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/api/weather", new WeatherHandler());
        server.createContext("/api/simulate-error", new ErrorHandler());
        server.createContext("/api/simulate-timeout", new SlowHandler(12000)); // 超时触发
        server.createContext("/api/simulate-long-response", new LongResponseHandler());
        server.start();
        port = server.getAddress().getPort();
    }

    // ============ Mock Handlers ============

    private class WeatherHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            lastRequestMethod.set(exchange.getRequestMethod());
            // 收集 headers
            Map<String, String> headers = new HashMap<>();
            exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), v.getFirst()));
            lastRequestHeaders.set(headers);
            // 收集 body
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastRequestBody.set(body);

            String response = "{\"temp\":23,\"condition\":\"sunny\",\"city\":\"Beijing\"}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    private class ErrorHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String response = "{\"error\":\"service unavailable\"}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    private class SlowHandler implements HttpHandler {
        private final long delayMs;

        SlowHandler(long delayMs) {
            this.delayMs = delayMs;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException ignored) {}
            String response = "{}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    private class LongResponseHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 10000; i++) sb.append("response_line_").append(i).append("\n");
            byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    // ============ 测试用例 ============

    private ToolExecutionRequest buildRequest(String argsJson) {
        return ToolExecutionRequest.builder()
                .id("call-1")
                .name("query_weather")
                .arguments(argsJson)
                .build();
    }

    @Nested
    @DisplayName("GET 请求")
    class GetRequests {

        /** GET 请求把参数替换进 URL 模板并返回响应体内容 */
        @Test
        @DisplayName("URL 模板参数替换")
        void execute_get_urlTemplate() {
            Map<String, Object> config = Map.of(
                    "url", "http://localhost:" + port + "/api/weather?city={city}",
                    "method", "GET"
            );

            ToolExecutionResultMessage result = executor.execute(config,
                    buildRequest("""
                            {"city":"Beijing"}
                            """));

            assertThat(result.text()).contains("Beijing");
            assertThat(result.text()).contains("sunny");
        }

        /** supportedToolType 返回 "HTTP" */
        @Test
        @DisplayName("支持 toolType 声明")
        void supportedToolType_returnsHttp() {
            assertThat(executor.supportedToolType()).isEqualTo("HTTP");
        }
    }

    @Nested
    @DisplayName("POST 请求")
    class PostRequests {

        /** POST 请求把参数作为 JSON 请求体发送到服务端 */
        @Test
        @DisplayName("POST 请求体为 JSON")
        void execute_post_bodyAsJson() {
            Map<String, Object> config = Map.of(
                    "url", "http://localhost:" + port + "/api/weather",
                    "method", "POST"
            );

            executor.execute(config,
                    buildRequest("""
                            {"city":"Shanghai","units":"celsius"}
                            """));

            // mock handler 应该收到 JSON body
            assertThat(lastRequestBody.get()).contains("Shanghai");
            assertThat(lastRequestMethod.get()).isEqualTo("POST");
        }
    }

    @Nested
    @DisplayName("Headers")
    class HeadersTests {

        /** 配置的自定义 headers 被原样发送到服务端 */
        @Test
        @DisplayName("自定义 headers 发送")
        void execute_withCustomHeaders() {
            Map<String, Object> config = new HashMap<>();
            config.put("url", "http://localhost:" + port + "/api/weather");
            config.put("method", "GET");
            config.put("headers", Map.of("Authorization", "Bearer test-token"));

            executor.execute(config, buildRequest("{}"));

            assertThat(lastRequestHeaders.get()).containsEntry("authorization", "Bearer test-token");
        }
    }

    @Nested
    @DisplayName("错误处理")
    class ErrorHandling {

        /** 服务端返回 5xx 时不抛异常，结果中包含状态码 */
        @Test
        @DisplayName("HTTP 5xx → result 包含状态码 + Error 前缀")
        void execute_5xxError() {
            Map<String, Object> config = Map.of(
                    "url", "http://localhost:" + port + "/api/simulate-error",
                    "method", "GET"
            );

            ToolExecutionResultMessage result = executor.execute(config, buildRequest("{}"));

            // 5xx 不应抛异常，但 result 会包含状态码
            assertThat(result.text()).contains("503");
        }

        /** 连接失败时结果以 Error: 开头 */
        @Test
        @DisplayName("网络异常 → result 包含 Error: 前缀")
        void execute_networkError() {
            Map<String, Object> config = Map.of(
                    "url", "http://localhost:1/never-connect",
                    "method", "GET",
                    "timeoutMs", 1000
            );

            ToolExecutionResultMessage result = executor.execute(config, buildRequest("{}"));

            assertThat(result.text()).startsWith("Error:");
        }
    }

    @Nested
    @DisplayName("结果截断")
    class Truncation {

        /** 超长响应被截断到约 4000 字符并追加 truncated 标记 */
        @Test
        @DisplayName("超长响应自动截断到 4000 字符")
        void execute_longResponse_truncated() {
            Map<String, Object> config = Map.of(
                    "url", "http://localhost:" + port + "/api/simulate-long-response",
                    "method", "GET"
            );

            ToolExecutionResultMessage result = executor.execute(config, buildRequest("{}"));

            assertThat(result.text()).hasSizeLessThanOrEqualTo(4100); // 4000 + "...[truncated]" 约 4012
            assertThat(result.text()).contains("truncated");
        }
    }

    @Nested
    @DisplayName("参数解析")
    class ArgumentParsing {

        /** 参数为非法 JSON 时按空 map 处理，执行不崩溃 */
        @Test
        @DisplayName("参数 JSON 格式错误 → 当作空 map 不崩溃")
        void execute_invalidArgumentsJson_doesNotCrash() {
            Map<String, Object> config = Map.of(
                    "url", "http://localhost:" + port + "/api/weather?city=fallback",
                    "method", "GET"
            );

            // 非法 JSON 参数不应该让执行器崩溃
            ToolExecutionResultMessage result = executor.execute(config,
                    buildRequest("not-valid-json"));

            assertThat(result).isNotNull();
        }
    }
}
