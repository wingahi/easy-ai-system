package com.wgh.rag.storage.service;

/**
 * 写入结果。key 为逻辑 key，与底层实现（本地相对路径 / OSS 对象名）解耦。
 */
public record StoredObject(String key, long size, String contentType) {
}
