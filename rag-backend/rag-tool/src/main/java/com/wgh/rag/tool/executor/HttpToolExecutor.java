package com.wgh.rag.tool.executor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP 工具执行器 —— 根据 executorConfig 里的 url 模板 + method + headers 发起 HTTP 调用
 *
 * executorConfig JSON 示例：
 * <pre>
 * {
 *   "url": "https://api.weather.com/q?city={city}&units={units}",
 *   "method": "GET",
 *   "headers": { "Authorization": "Bearer ${WEATHER_API_KEY}" },
 *   "timeoutMs": 5000
 * }
 * </pre>
 *
 * 模板占位符 {paramName} 从 ToolExecutionRequest.arguments JSON 里取值
 */
@Slf4j
@Component
public class HttpToolExecutor implements ToolExecutor {

    private static final String TYPE = "HTTP";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)\\}");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final HttpClient httpClient;

    public HttpToolExecutor() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public String supportedToolType() {
        return TYPE;
    }

    @Override
    public ToolExecutionResultMessage execute(Map<String, Object> executorConfig, ToolExecutionRequest request) {
        long start = System.currentTimeMillis();
        try {
            String url = (String) executorConfig.get("url");
            String method = String.valueOf(executorConfig.getOrDefault("method", "GET")).toUpperCase();
            Map<String, String> headers = parseHeaders(executorConfig.get("headers"));
            int timeoutMs = ((Number) executorConfig.getOrDefault("timeoutMs", 10000)).intValue();

            // 解析参数
            Map<String, Object> args = parseArguments(request.arguments());

            // 渲染 URL 模板
            String renderedUrl = renderTemplate(url, args);

            // 渲染 headers 模板（支持 ${ENV_VAR}）
            Map<String, String> renderedHeaders = new HashMap<>();
            headers.forEach((k, v) -> renderedHeaders.put(k, renderEnvVars(v)));

            log.info("HTTP 工具调用: {} {} (args={})", method, renderedUrl, args);

            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(renderedUrl))
                    .timeout(Duration.ofMillis(timeoutMs));

            renderedHeaders.forEach(reqBuilder::header);

            // 处理 body
            HttpRequest.BodyPublisher bodyPublisher = HttpRequest.BodyPublishers.noBody();
            if ("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method)) {
                bodyPublisher = HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(args));
                reqBuilder.header("Content-Type", "application/json");
            }

            switch (method) {
                case "GET" -> reqBuilder.GET();
                case "POST" -> reqBuilder.POST(bodyPublisher);
                case "PUT" -> reqBuilder.PUT(bodyPublisher);
                case "DELETE" -> reqBuilder.method("DELETE", bodyPublisher);
                default -> reqBuilder.method(method, bodyPublisher);
            }

            HttpResponse<String> response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
            long latency = System.currentTimeMillis() - start;

            String result;
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                result = response.body();
            } else {
                result = "HTTP " + response.statusCode() + " - " + response.body();
                log.warn("HTTP 工具调用返回非 2xx: {} {} → {} ({})", method, renderedUrl, response.statusCode(), latency + "ms");
            }

            // 截断过长结果避免撑爆上下文
            if (result.length() > 4000) {
                result = result.substring(0, 4000) + "...[truncated]";
            }

            return new ToolExecutionResultMessage(request.id(), request.name(), result);

        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            log.error("HTTP 工具调用失败: {} {} ({})", executorConfig.get("url"), request.arguments(), latency + "ms", e);
            return new ToolExecutionResultMessage(
                    request.id(), request.name(),
                    "Error: " + e.getClass().getSimpleName() + " - " + e.getMessage()
            );
        }
    }

    private Map<String, String> parseHeaders(Object headers) {
        if (headers instanceof Map) {
            Map<String, String> result = new HashMap<>();
            ((Map<?, ?>) headers).forEach((k, v) -> result.put(String.valueOf(k), String.valueOf(v)));
            return result;
        }
        return new HashMap<>();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArguments(String argumentsJson) {
        try {
            return MAPPER.readValue(argumentsJson, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("工具参数解析失败，当作空 map: {}", argumentsJson, e);
            return new HashMap<>();
        }
    }

    private String renderTemplate(String template, Map<String, Object> args) {
        if (template == null) return "";
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String key = m.group(1);
            Object value = args.get(key);
            String replacement = value != null ? String.valueOf(value) : m.group(0);
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String renderEnvVars(String value) {
        if (value == null) return "";
        if (value.startsWith("${") && value.endsWith("}")) {
            String envKey = value.substring(2, value.length() - 1);
            String envVal = System.getenv(envKey);
            return envVal != null ? envVal : value;
        }
        return value;
    }
}
