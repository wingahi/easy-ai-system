package com.wgh.rag.kb.port;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;

import java.util.List;

/**
 * 关键词检索端口。
 * <p>
 * 当前实现基于 PostgreSQL pg_trgm；业务侧只依赖接口，便于未来替换为其他全文检索实现。
 */
public interface KeywordSearchPort {

    /**
     * 关键词召回。
     */
    List<EmbeddingMatch<TextSegment>> search(String tableName, String query, int topN);
}
