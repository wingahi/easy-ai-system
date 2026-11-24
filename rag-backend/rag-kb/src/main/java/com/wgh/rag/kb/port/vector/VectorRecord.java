package com.wgh.rag.kb.port.vector;

import java.util.Map;

/**
 * 一条待写入的向量记录。
 *
 * @param id       向量记录 ID；为空时由实现生成（要求实现支持幂等写入）
 * @param text     chunk 原文
 * @param metadata 检索过滤/来源定位使用的元数据
 * @param vector   已计算好的向量；为空时由实现按集合绑定的模型生成
 */
public record VectorRecord(String id, String text, Map<String, Object> metadata, float[] vector) {

    public VectorRecord {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static VectorRecord of(String text, Map<String, Object> metadata, float[] vector) {
        return new VectorRecord(null, text, metadata, vector);
    }
}
