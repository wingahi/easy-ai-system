package com.wgh.rag.kb.port;

/**
 * 可被 {@link com.wgh.rag.kb.service.VectorStoreRouter} 选择的具体向量后端。
 *
 * <p>单独抽一层标记接口，避免路由器把自己也注入进候选后端集合。</p>
 */
public interface VectorStoreBackend extends VectorStorePort {

    /** 配置中使用的后端名称，例如 pgvector / memory。 */
    String backendName();
}
