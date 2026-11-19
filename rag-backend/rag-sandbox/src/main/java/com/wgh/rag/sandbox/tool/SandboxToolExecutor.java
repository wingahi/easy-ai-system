package com.wgh.rag.sandbox.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.sandbox.model.SandboxException;
import com.wgh.rag.sandbox.model.SandboxRequest;
import com.wgh.rag.sandbox.model.SandboxResult;
import com.wgh.rag.sandbox.service.CodeSandboxService;
import com.wgh.rag.tool.constant.ToolConstants;
import com.wgh.rag.tool.executor.ToolExecutor;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * 沙箱工具执行器：toolType = {@code SANDBOX}。
 *
 * <p>通过现有 ToolConfig 注册机制生效；数据库中默认没有 SANDBOX 记录，
 * 因此模型默认看不到该工具。管理员在 {@code wgh_tool} 中新增记录并挂到窗口后才会暴露。
 *
 * <p>参数：{@code language}（PYTHON/JAVASCRIPT）、{@code code}、{@code stdin}、{@code timeoutMs}。
 * 执行结果只回传截断后的输出，不包含源码正文。
 */
@Slf4j
public class SandboxToolExecutor implements ToolExecutor {

    public static final String TOOL_TYPE = "SANDBOX";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEFAULT_LANGUAGE = "PYTHON";

    private final CodeSandboxService sandbox;

    public SandboxToolExecutor(CodeSandboxService sandbox) {
        this.sandbox = sandbox;
    }

    @Override
    public String supportedToolType() {
        return TOOL_TYPE;
    }

    @Override
    public ToolExecutionResultMessage execute(Map<String, Object> executorConfig,
                                              ToolExecutionRequest request) {
        if (!sandbox.isEnabled()) {
            return error(request, "SandboxDisabledException - 代码沙箱未启用");
        }
        Map<String, Object> args = parseArguments(request.arguments());
        String language = stringArg(args, "language", stringArg(executorConfig, "language", DEFAULT_LANGUAGE));
        String code = stringArg(args, "code", null);
        String stdin = stringArg(args, "stdin", null);
        Long timeoutMs = longArg(args, "timeoutMs");

        long start = System.currentTimeMillis();
        try {
            SandboxResult result = sandbox.execute(new SandboxRequest(language, code, stdin, timeoutMs));
            log.info("沙箱工具调用完成: lang={}, exit={}, timedOut={}, truncated={}, isolation={}, {}ms",
                    result.language(), result.exitCode(), result.timedOut(), result.truncated(),
                    result.isolation(), System.currentTimeMillis() - start);
            return new ToolExecutionResultMessage(request.id(), request.name(), format(result));
        } catch (SandboxException e) {
            log.warn("沙箱工具调用被拒绝: {} ({}ms)", e.getMessage(), System.currentTimeMillis() - start);
            return error(request, "SandboxException - " + e.getMessage());
        } catch (Exception e) {
            log.error("沙箱工具调用异常: {} ({}ms)", request.name(), System.currentTimeMillis() - start, e);
            return error(request, e.getClass().getSimpleName() + " - " + e.getMessage());
        }
    }

    private String format(SandboxResult result) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("exitCode=").append(result.exitCode())
                .append(" timedOut=").append(result.timedOut())
                .append(" truncated=").append(result.truncated())
                .append(" durationMs=").append(result.durationMillis())
                .append(" isolation=").append(result.isolation())
                .append('\n');
        if (!result.stdout().isEmpty()) {
            sb.append("--- stdout ---\n").append(result.stdout()).append('\n');
        }
        if (!result.stderr().isEmpty()) {
            sb.append("--- stderr ---\n").append(result.stderr()).append('\n');
        }
        String text = sb.toString();
        if (text.length() > ToolConstants.MAX_RESULT_CHARS) {
            text = text.substring(0, ToolConstants.MAX_RESULT_CHARS) + "...[truncated]";
        }
        return text;
    }

    private ToolExecutionResultMessage error(ToolExecutionRequest request, String message) {
        return new ToolExecutionResultMessage(request.id(), request.name(), "Error: " + message);
    }

    private Map<String, Object> parseArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(argumentsJson, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            log.warn("沙箱工具参数解析失败: {}", e.getMessage());
            return Map.of();
        }
    }

    private String stringArg(Map<String, Object> args, String key, String fallback) {
        Object value = args.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private Long longArg(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
