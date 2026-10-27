package com.wgh.rag.tool.demo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 演示用的 FUNCTION_CALLING handler —— 通过反射调用，模拟查询用户订单。
 *
 * 种子数据 executorConfig:
 * <pre>{"handlerClass":"com.wgh.rag.tool.demo.OrderQueryHandler","method":"query"}</pre>
 *
 * 返回 JSON 字符串（不依赖 Jackson，避免 rag-tool 模块额外引入 databind）。
 */
public class OrderQueryHandler {

    /** 模拟订单库 */
    private static final List<Map<String, Object>> ORDERS = List.of(
            Map.of("orderNo", "SO202610270001", "userId", "U1001",
                    "status", "paid",      "amount", 129.00, "product", "无线键盘"),
            Map.of("orderNo", "SO202610270002", "userId", "U1001",
                    "status", "pending",   "amount",  59.90, "product", "鼠标垫"),
            Map.of("orderNo", "SO202610270003", "userId", "U1002",
                    "status", "completed", "amount", 899.00, "product", "显示器"),
            Map.of("orderNo", "SO202610270004", "userId", "U1002",
                    "status", "paid",      "amount",  45.50, "product", "USB-C 数据线")
    );

    /**
     * 按用户 ID + 可选状态查询订单
     *
     * @param args { "userId": "U1001", "status": "all|pending|paid|completed" }
     * @return JSON 字符串
     */
    public static String query(Map<String, Object> args) {
        String userId = args != null && args.get("userId") != null ? args.get("userId").toString() : null;
        String status = args != null && args.get("status") != null ? args.get("status").toString() : "all";
        if (userId == null || userId.isBlank()) {
            return "{\"error\":\"userId 不能为空\"}";
        }

        List<Map<String, Object>> hits = new ArrayList<>();
        for (Map<String, Object> o : ORDERS) {
            if (!userId.equals(o.get("userId"))) continue;
            if (!"all".equalsIgnoreCase(status) && !status.equalsIgnoreCase((String) o.get("status"))) continue;
            hits.add(o);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("userId", userId);
        out.put("status", status);
        out.put("count", hits.size());
        out.put("orders", hits);
        return toJson(out);
    }

    // ===== 极简 JSON 序列化（仅支持 String/Number/Boolean/Map/List/null） =====

    private static String toJson(Object v) {
        if (v == null) return "null";
        if (v instanceof String s) return "\"" + escape(s) + "\"";
        if (v instanceof Number || v instanceof Boolean) return v.toString();
        if (v instanceof Map<?, ?> m) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(",");
                sb.append("\"").append(escape(String.valueOf(e.getKey()))).append("\":").append(toJson(e.getValue()));
                first = false;
            }
            return sb.append("}").toString();
        }
        if (v instanceof List<?> list) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(toJson(list.get(i)));
            }
            return sb.append("]").toString();
        }
        return "\"" + escape(String.valueOf(v)) + "\"";
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
