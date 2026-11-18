package com.wgh.rag.tool.fallback;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 工具结果信封构造器（纯函数）。
 *
 * <p>不改变工具返回文本，只做「分页 + 摘要 + 状态」三个派生视图，
 * 供上层按需把长结果折叠成要点，或分页回读。
 */
public final class ToolResultNormalizer {

    /** 单页默认字符数（与 HttpToolExecutor 既有 4000 字符上限保持一致） */
    public static final int DEFAULT_PAGE_CHARS = 4000;
    /** 默认摘要字符数 */
    public static final int DEFAULT_SUMMARY_CHARS = 200;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolResultNormalizer() {
    }

    /** 从第 0 页构造信封。 */
    public static ToolResultEnvelope firstPage(String toolName, String text, int pageChars, int summaryChars) {
        return page(toolName, text, 0, pageChars, summaryChars);
    }

    /**
     * 按游标分页构造信封。
     *
     * @param offset 已消费字符偏移（即上一页的 nextCursor）
     */
    public static ToolResultEnvelope page(String toolName, String text, int offset, int pageChars, int summaryChars) {
        String safe = text != null ? text : "";
        int pageSize = pageChars > 0 ? pageChars : DEFAULT_PAGE_CHARS;
        int summaryLimit = summaryChars > 0 ? summaryChars : DEFAULT_SUMMARY_CHARS;
        int start = Math.max(Math.min(offset, safe.length()), 0);
        int end = Math.min(start + pageSize, safe.length());
        boolean truncated = end < safe.length();
        return new ToolResultEnvelope(
                toolName,
                ToolFailureClassifier.isSuccess(safe),
                ToolFailureClassifier.classify(safe),
                safe.substring(start, end),
                summarize(safe, summaryLimit),
                truncated,
                truncated ? String.valueOf(end) : null,
                safe.length());
    }

    /**
     * 生成摘要：
     * <ul>
     *   <li>JSON 对象：{@code JSON 对象，字段: a, b, c（共 n 个）}</li>
     *   <li>JSON 数组：{@code JSON 数组，共 n 项} + 首项预览</li>
     *   <li>普通文本：去空白后的首段</li>
     * </ul>
     */
    public static String summarize(String text, int maxChars) {
        String safe = text != null ? text.strip() : "";
        if (safe.isEmpty()) {
            return "";
        }
        int limit = maxChars > 0 ? maxChars : DEFAULT_SUMMARY_CHARS;
        String summary = jsonSummary(safe);
        if (summary == null) {
            summary = safe.replaceAll("\\s+", " ");
        }
        return summary.length() <= limit ? summary : summary.substring(0, limit) + "…";
    }

    private static String jsonSummary(String text) {
        char first = text.charAt(0);
        if (first != '{' && first != '[') {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(text);
            if (node.isObject()) {
                List<String> names = new ArrayList<>();
                Iterator<Map.Entry<String, JsonNode>> it = node.fields();
                while (it.hasNext()) {
                    names.add(it.next().getKey());
                }
                String preview = names.size() > 10
                        ? String.join(", ", names.subList(0, 10)) + ", …"
                        : String.join(", ", names);
                return "JSON 对象，字段: " + preview + "（共 " + names.size() + " 个）";
            }
            if (node.isArray()) {
                String preview = node.isEmpty() ? "" : "，首项: " + truncate(node.get(0).toString(), 80);
                return "JSON 数组，共 " + node.size() + " 项" + preview;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String truncate(String text, int maxChars) {
        return text.length() <= maxChars ? text : text.substring(0, maxChars) + "…";
    }
}
