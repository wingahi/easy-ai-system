package com.wgh.rag.kb.port.vector;

/**
 * 集合级元数据：后端类型、维度、行数。用于管理端展示与迁移前校验。
 */
public record CollectionInfo(String name, String backend, Integer dimension, long count) {
}
