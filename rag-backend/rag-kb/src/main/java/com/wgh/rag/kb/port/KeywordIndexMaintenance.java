package com.wgh.rag.kb.port;

/**
 * 关键词索引维护端口（后端无关契约）。
 *
 * <p>删除文件、删除知识库、重建集合时，编排层通过本端口把同一动作同时施加到
 * 关键词索引与向量索引两条路径；具体实现负责翻译为各自方言。默认实现为
 * PG pg_trgm（索引与被检索表同源，删除行无需额外动作）。</p>
 */
public interface KeywordIndexMaintenance {

    /** 实现的后端名，与 {@link KeywordSearchBackend#backendName()} 对齐。 */
    String backendName();

    /**
     * 删除某文件在关键词索引中的文档（幂等）。
     * <p>pg_trgm 的索引与向量表同源，行删除由向量路完成，本实现为空操作。</p>
     */
    void deleteByFileId(String collection, Long fileId);

    /** 删除整个集合对应的关键词索引（幂等）。 */
    void dropCollection(String collection);

    /**
     * 确保集合的关键词索引存在（重建后预建，避免首次检索触发 DDL）。
     * <p>无法预建索引的实现可退化为空操作。</p>
     */
    void ensureIndex(String collection);
}
