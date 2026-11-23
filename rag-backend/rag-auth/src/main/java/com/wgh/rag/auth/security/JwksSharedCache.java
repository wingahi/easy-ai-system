package com.wgh.rag.auth.security;

import java.time.Duration;

/**
 * 跨实例 JWKS 共享缓存抽象。
 * <p>
 * 本地缓存（{@link JwksProvider}）仍是第一层，本接口只负责多实例之间共享原始 JWKS 文档，
 * 避免每个实例分别回源 IdP。实现不可用时不抛异常，由调用方回退到本地缓存 + 回源。
 */
public interface JwksSharedCache {

    /** 共享缓存是否可用（配置开启且底层存储可用）。 */
    boolean enabled();

    /**
     * 读取共享 JWKS 原始文档。
     *
     * @param jwksUri JWKS 端点
     * @return 原始 JSON；未命中、存储不可用或读取失败时返回 {@code null}
     */
    String get(String jwksUri);

    /**
     * 写入共享 JWKS 原始文档。
     *
     * @param jwksUri JWKS 端点
     * @param body    原始 JSON
     * @param ttl     过期时间；非正数表示不写入
     */
    void put(String jwksUri, String body, Duration ttl);

    /** 空实现：用于未启用共享缓存或单元测试直接构造 {@link JwksProvider}。 */
    JwksSharedCache NOOP = new JwksSharedCache() {
        @Override
        public boolean enabled() {
            return false;
        }

        @Override
        public String get(String jwksUri) {
            return null;
        }

        @Override
        public void put(String jwksUri, String body, Duration ttl) {
            // no-op
        }
    };
}
