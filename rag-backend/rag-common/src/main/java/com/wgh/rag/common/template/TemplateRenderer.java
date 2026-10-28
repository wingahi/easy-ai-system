package com.wgh.rag.common.template;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {{path}} 模板渲染器 —— rag-workflow 与 rag-agent-multi 共用的渲染核心。
 * <p>
 * 本类只统一可共享的部分：占位符语法（{@link #VAR_PATTERN}）、替换循环、
 * Map/List 路径遍历（{@link #traverse}）与默认值字符串化（{@link #stringify}）。
 * 路径解析语义（vars / nodeOutputs / data 等命名空间的取值优先级）由调用方
 * 通过 {@link PathResolver} 注入，以保持各调用点既有行为不变。
 */
public final class TemplateRenderer {

    /** 占位符语法：{{path}}，允许首尾空白，路径段由字母数字下划线、点、连字符组成 */
    public static final Pattern VAR_PATTERN = Pattern.compile("\\{\\{\\s*([\\w.\\-]+)\\s*\\}\\}");

    private static final ObjectMapper DEFAULT_OBJECT_MAPPER = new ObjectMapper();

    private TemplateRenderer() {
    }

    /** 路径解析策略：输入占位符内的 path，返回解析到的原始值（未命中返回 null） */
    @FunctionalInterface
    public interface PathResolver {
        Object resolve(String path);
    }

    /** 用默认字符串化规则渲染模板中的所有 {{path}} */
    public static String render(String template, PathResolver resolver) {
        return render(template, resolver, TemplateRenderer::stringify);
    }

    /** 用指定字符串化规则渲染模板中的所有 {{path}}；null 模板渲染为空串 */
    public static String render(String template, PathResolver resolver, Function<Object, String> stringifier) {
        if (template == null || template.isEmpty()) return template == null ? "" : template;
        Matcher matcher = VAR_PATTERN.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            Object value = resolver.resolve(matcher.group(1));
            matcher.appendReplacement(sb, Matcher.quoteReplacement(stringifier.apply(value)));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /** 默认字符串化：null→空串；String 原样；Map/List 序列化为 JSON（失败退回 String.valueOf）；其余 String.valueOf */
    public static String stringify(Object value) {
        if (value == null) return "";
        if (value instanceof String s) return s;
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            try {
                return DEFAULT_OBJECT_MAPPER.writeValueAsString(value);
            } catch (Exception e) {
                return String.valueOf(value);
            }
        }
        return String.valueOf(value);
    }

    /** 从 current 出发按 parts[from..] 逐段遍历 Map/List；不可遍历、键缺失或下标越界返回 null */
    @SuppressWarnings("unchecked")
    public static Object traverse(Object current, String[] parts, int from) {
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
}
