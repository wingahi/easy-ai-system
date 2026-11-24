package com.wgh.rag.kb.port;

/**
 * 可被 {@link com.wgh.rag.kb.service.KeywordSearchRouter} 选择的具体关键词后端。
 */
public interface KeywordSearchBackend extends KeywordSearchPort {

    /** 配置中使用的后端名称，例如 pgtrgm / memory。 */
    String backendName();
}
