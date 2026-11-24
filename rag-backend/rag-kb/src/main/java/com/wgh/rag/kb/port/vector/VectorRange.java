package com.wgh.rag.kb.port.vector;

/**
 * 数值型元数据范围过滤（闭区间）；min/max 为空表示该侧不限制。
 */
public record VectorRange(Double min, Double max) {

    public static VectorRange atLeast(double min) {
        return new VectorRange(min, null);
    }

    public static VectorRange atMost(double max) {
        return new VectorRange(null, max);
    }

    public static VectorRange between(double min, double max) {
        return new VectorRange(min, max);
    }
}
