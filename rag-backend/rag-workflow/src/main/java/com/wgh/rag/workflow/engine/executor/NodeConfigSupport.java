package com.wgh.rag.workflow.engine.executor;

import com.wgh.rag.workflow.dto.GraphNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 节点配置取值工具 —— 引擎与各节点执行器共用（与原 WorkflowEngine 私有实现保持一致）。
 */
public final class NodeConfigSupport {

    private NodeConfigSupport() {
    }

    public static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    public static Long asLong(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static int asInt(Object v, int defaultValue) {
        Long l = asLong(v);
        return l == null ? defaultValue : l.intValue();
    }

    public static int intConfig(GraphNode node, String key, int defaultValue) {
        return asInt(node.getConfig() != null ? node.getConfig().get(key) : null, defaultValue);
    }

    public static List<Long> asLongList(Object v) {
        List<Long> ids = new ArrayList<>();
        if (v == null) return ids;
        if (v instanceof List<?> list) {
            for (Object o : list) {
                Long id = asLong(o);
                if (id != null) ids.add(id);
            }
            return ids;
        }
        for (String part : String.valueOf(v).split(",")) {
            Long id = asLong(part.trim());
            if (id != null) ids.add(id);
        }
        return ids;
    }
}
