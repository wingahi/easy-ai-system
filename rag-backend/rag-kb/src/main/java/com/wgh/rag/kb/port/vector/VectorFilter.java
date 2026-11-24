package com.wgh.rag.kb.port.vector;

import java.util.Map;

/**
 * 后端无关的检索过滤条件。
 *
 * <p>统一用「元数据键值 + 数值范围 + 租户」描述，具体后端各自翻译为原生过滤语法：
 * pgvector 翻译为 {@code metadata->>'key'} 谓词，独立向量库翻译为 payload filter。
 * 租户过滤由编排层强制传入，不允许依赖后端自身的权限模型。</p>
 *
 * @param tenantId 租户编码（VARCHAR code，与 {@code TenantContext} 口径一致）；为 null 表示不按租户过滤
 */
public record VectorFilter(String tenantId, Map<String, Object> equals, Map<String, VectorRange> ranges) {

    private static final VectorFilter NONE = new VectorFilter(null, Map.of(), Map.of());

    public VectorFilter {
        equals = equals == null ? Map.of() : Map.copyOf(equals);
        ranges = ranges == null ? Map.of() : Map.copyOf(ranges);
    }

    public static VectorFilter none() {
        return NONE;
    }

    public static VectorFilter byTenant(String tenantId) {
        return new VectorFilter(tenantId, Map.of(), Map.of());
    }

    public static VectorFilter byEquals(Map<String, Object> equals) {
        return new VectorFilter(null, equals, Map.of());
    }

    public boolean isEmpty() {
        return tenantId == null && equals.isEmpty() && ranges.isEmpty();
    }
}
