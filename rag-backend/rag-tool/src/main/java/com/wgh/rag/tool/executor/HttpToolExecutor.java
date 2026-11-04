package com.wgh.rag.tool.executor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.common.constant.HttpConstants;
import com.wgh.rag.tool.constant.ToolConstants;
import com.wgh.rag.tool.security.SsrfBlockedException;
import com.wgh.rag.tool.security.SsrfGuard;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
 *
 * <p>所有出站请求统一经过 {@link SsrfGuard}：协议/主机/IP 校验、重定向逐跳校验、响应大小与超时上限。
 */
@Slf4j
@Component
public class HttpToolExecutor implements ToolExecutor {

    private static final String TYPE = "HTTP";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)\\}");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 单次请求超时上限（毫秒），防止配置超大值长时间占用线程 */
    private static final int MAX_TIMEOUT_MS = 30_000;

    private final HttpClient httpClient;
    private final SsrfGuard ssrfGuard;

    public HttpToolExecutor(SsrfGuard ssrfGuard) {
        this.ssrfGuard = ssrfGuard;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(ToolConstants.HTTP_CONNECT_TIMEOUT_SECONDS))
                // 关闭自动重定向，改为手动跟随并逐跳做 SSRF 校验
                .followRedirects(HttpClient.Redirect.NEVER)
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
            int timeoutMs = Math.min(
                    ((Number) executorConfig.getOrDefault("timeoutMs", ToolConstants.DEFAULT_HTTP_TIMEOUT_MS)).intValue(),
                    MAX_TIMEOUT_MS);
            if (timeoutMs <= 0) {
                timeoutMs = ToolConstants.DEFAULT_HTTP_TIMEOUT_MS;
            }

            // 解析参数
            Map<String, Object> args = parseArguments(request.arguments());

            // 渲染 URL 模板
            String renderedUrl = renderTemplate(url, args);

            // 渲染 headers 模板（支持 ${ENV_VAR}）
            Map<String, String> renderedHeaders = new HashMap<>();
            headers.forEach((k, v) -> renderedHeaders.put(k, renderEnvVars(v)));

            // 首跳 SSRF 校验
            URI currentUri = ssrfGuard.validate(renderedUrl);
            log.info("HTTP 工具调用: {} {} (args={})", method, currentUri, args);

            String currentMethod = method;
            String requestBody = isBodyMethod(method) ? MAPPER.writeValueAsString(args) : null;

            HttpResponse<InputStream> response = send(currentUri, currentMethod, requestBody, renderedHeaders, timeoutMs);

            // 手动跟随重定向，每跳重新校验
            int redirects = 0;
            while (isRedirect(response.statusCode())) {
                int status = response.statusCode();
                Optional<String> location = response.headers().firstValue("Location");
                closeQuietly(response.body());
                if (location.isEmpty()) {
                    break;
                }
                if (redirects >= ssrfGuard.getMaxRedirects()) {
                    throw new SsrfBlockedException("重定向次数超过上限: " + ssrfGuard.getMaxRedirects());
                }
                URI nextUri = currentUri.resolve(location.get().trim());
                // 逐跳 SSRF 校验，禁止 30x 跳转到内网/元数据地址
                currentUri = ssrfGuard.validate(nextUri);
                if (status == 301 || status == 302 || status == 303) {
                    currentMethod = "GET";
                    requestBody = null;
                }
                response = send(currentUri, currentMethod, requestBody, renderedHeaders, timeoutMs);
                redirects++;
            }

            long latency = System.currentTimeMillis() - start;
            String responseBody = readLimited(response.body());

            String result;
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                result = responseBody;
            } else {
                result = "HTTP " + response.statusCode() + " - " + responseBody;
                log.warn("HTTP 工具调用返回非 2xx: {} {} → {} ({})", currentMethod, currentUri, response.statusCode(), latency + "ms");
            }

            // 截断过长结果避免撑爆上下文
            if (result != null && result.length() > ToolConstants.MAX_RESULT_CHARS) {
                result = result.substring(0, ToolConstants.MAX_RESULT_CHARS) + "...[truncated]";
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

    private boolean isBodyMethod(String method) {
        return "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method);
    }

    private boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private HttpResponse<InputStream> send(URI uri, String method, String body,
                                           Map<String, String> headers, int timeoutMs)
            throws IOException, InterruptedException {
        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofMillis(timeoutMs));
        headers.forEach(reqBuilder::header);

        if (body == null) {
            switch (method) {
                case "GET" -> reqBuilder.GET();
                case "DELETE" -> reqBuilder.DELETE();
                default -> reqBuilder.method(method, HttpRequest.BodyPublishers.noBody());
            }
        } else {
            reqBuilder.header(HttpConstants.HEADER_CONTENT_TYPE, HttpConstants.CONTENT_TYPE_JSON);
            reqBuilder.method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());
    }

    /**
     * 读取响应体并限制最大字节数，避免被超大响应拖垮内存。
     */
    private String readLimited(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        long limit = ssrfGuard.getMaxResponseBytes();
        try (InputStream input = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            long remaining = limit;
            boolean truncated = false;
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (remaining <= 0) {
                    truncated = true;
                    break;
                }
                int toWrite = (int) Math.min(read, remaining);
                out.write(buffer, 0, toWrite);
                remaining -= toWrite;
                if (toWrite < read) {
                    truncated = true;
                    break;
                }
            }
            String text = out.toString(StandardCharsets.UTF_8);
            return truncated ? text + "...[response truncated]" : text;
        }
    }

    private void closeQuietly(InputStream in) {
        if (in == null) return;
        try {
            in.close();
        } catch (IOException ignored) {
            // 忽略关闭异常
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
