package com.wgh.rag.workflow.engine;

import com.wgh.rag.workflow.dto.RunContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 变量引用解析器 —— 支持 {{input}} / {{vars.x}} / {{nodeId.text}} / {{nodeId.sources.0.fileName}} / {{loop.iteration}}。
 * 未解析到的路径渲染为空串，避免调试期因缺省数据中断。
 */
@Component
public class VariableResolver {

    private static final Pattern VAR = Pattern.compile("\\{\\{\\s*([\\w.\\-]+)\\s*\\}\\}");

    /** 渲染模板中的所有 {{path}} */
    public String render(String template, RunContext ctx) {
        if (template == null || template.isEmpty()) return template == null ? "" : template;
        Matcher matcher = VAR.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            Object value = resolvePath(matcher.group(1), ctx);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(stringify(value)));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /** 解析单个路径 */
    public Object resolvePath(String path, RunContext ctx) {
        if (path == null || ctx == null) return null;
        String[] parts = path.split("\\.");
        if (parts.length == 0) return null;

        switch (parts[0]) {
            case "input" -> {
                return ctx.getVars().get("input");
            }
            case "last" -> {
                return ctx.getVars().get("lastOutput");
            }
            case "loop" -> {
                if (parts.length > 1 && "iteration".equals(parts[1])) {
                    return ctx.getCurrentLoopIteration();
                }
                return null;
            }
            case "vars" -> {
                if (parts.length < 2) return null;
                Object current = ctx.getVars().get(parts[1]);
                return traverse(current, parts, 2);
            }
            default -> {
                Map<String, Object> output = ctx.getNodeOutputs().get(parts[0]);
                if (output == null) return null;
                Object current = output.get(parts.length > 1 ? parts[1] : "text");
                return traverse(current, parts, 2);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Object traverse(Object current, String[] parts, int from) {
        for (int i = from; i < parts.length && current != null; i++) {
            String key = parts[i];
            if (current instanceof Map<?, ?> map) {
                current = ((Map<String, Object>) map).get(key);
            } else if (current instanceof List<?> list) {
                try {
                    int idx = Integer.parseInt(key);
                    current = idx >= 0 && idx < list.size() ? list.get(idx) : null;
                } catch (NumberFormatException e) {
                    return null;
                }
            } else {
                return null;
            }
        }
        return current;
    }

    public String stringify(Object value) {
        if (value == null) return "";
        if (value instanceof String s) return s;
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            try {
                return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
            } catch (Exception e) {
                return String.valueOf(value);
            }
        }
        return String.valueOf(value);
    }
}
