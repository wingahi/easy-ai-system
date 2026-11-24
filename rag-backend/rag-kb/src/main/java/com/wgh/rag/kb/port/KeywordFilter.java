package com.wgh.rag.kb.port;

import java.util.Map;

/**
 * 关键词检索过滤条件（后端无关）。
 *
 * @param tenantId 租户编码（VARCHAR code，与 {@code TenantContext} 口径一致）；为 null 表示不按租户过滤
 */
public record KeywordFilter(String tenantId, Map<String, Object> equals) {

    private static final KeywordFilter NONE = new KeywordFilter(null, Map.of());

    public KeywordFilter {
        equals = equals == null ? Map.of() : Map.copyOf(equals);
    }

    public static KeywordFilter none() {
        return NONE;
    }

    public static KeywordFilter byTenant(String tenantId) {
        return new KeywordFilter(tenantId, Map.of());
    }
}
