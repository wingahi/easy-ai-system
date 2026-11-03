package com.wgh.rag.storage.service;

import java.io.InputStream;

/**
 * 可插拔文件存储服务：上层只依赖存储 key，不感知本地磁盘或对象存储的差异。
 * <p>
 * key 是稳定可推导的逻辑路径（见 {@link StorageKeys}），实际落盘/对象名由具体实现决定。
 */
public interface FileStorageService {

    /**
     * 写入对象。实现需保证失败时不留下半成品。
     *
     * @param key         逻辑 key（见 {@link StorageKeys}）
     * @param in          输入流（由调用方负责关闭）
     * @param size        预期大小（字节），仅作校验/元数据参考
     * @param contentType MIME 类型，可为 null
     */
    StoredObject store(String key, InputStream in, long size, String contentType);

    /**
     * 打开对象读取流，调用方负责关闭。
     */
    InputStream openStream(String key);

    /**
     * 删除对象。key 不存在时应当幂等返回。
     */
    void delete(String key);

    /**
     * 解析可直接访问的 URL：本地为静态访问前缀拼接，OSS 为公网或签名 URL。
     */
    String resolveUrl(String key);

    /**
     * 对象是否存在。
     */
    boolean exists(String key);
}
