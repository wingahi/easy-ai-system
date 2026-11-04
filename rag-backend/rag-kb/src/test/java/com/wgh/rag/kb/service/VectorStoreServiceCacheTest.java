package com.wgh.rag.kb.service;

import com.wgh.rag.config.property.PgVectorProperties;
import com.wgh.rag.llm.factory.ModelCacheManager;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EmbeddingStore 句柄缓存：同表同维度复用、不同维度隔离、LRU 淘汰、删表失效、HNSW 索引幂等。
 */
@DisplayName("向量存储 - 句柄缓存与 HNSW 索引")
class VectorStoreServiceCacheTest {

    private final DataSource dataSource = Mockito.mock(DataSource.class);
    private final Connection connection = Mockito.mock(Connection.class);
    private final Statement statement = Mockito.mock(Statement.class);

    private PgVectorProperties props;
    private VectorStoreService service;

    @BeforeEach
    void setUp() throws Exception {
        props = new PgVectorProperties();
        // 关闭 LangChain4j 建表 DDL，让测试只覆盖缓存与 HNSW 索引逻辑
        props.setCreateTable(false);
        props.setSkipCreateVectorExtension(true);
        props.setUseIndex(false);
        props.setUseHnswIndex(true);
        props.setStoreCacheMaxSize(2);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);

        service = new VectorStoreService(dataSource, props, Mockito.mock(ModelCacheManager.class));
    }

    @Test
    @DisplayName("同表同维度：重复调用复用同一个句柄，不再构建")
    void sameTableAndDimensionReusesHandle() {
        EmbeddingStore<TextSegment> first = service.createEmbeddingStore("kb_a", 1536);
        EmbeddingStore<TextSegment> second = service.createEmbeddingStore("kb_a", 1536);

        assertThat(second).isSameAs(first);
    }

    @Test
    @DisplayName("同表不同维度：按维度隔离，返回不同句柄")
    void differentDimensionIsolated() {
        EmbeddingStore<TextSegment> dim1536 = service.createEmbeddingStore("kb_a", 1536);
        EmbeddingStore<TextSegment> dim768 = service.createEmbeddingStore("kb_a", 768);

        assertThat(dim768).isNotSameAs(dim1536);
    }

    @Test
    @DisplayName("超过上限按 LRU 淘汰（最近访问的保留）")
    void evictsLeastRecentlyUsed() {
        EmbeddingStore<TextSegment> a = service.createEmbeddingStore("kb_a", 1536);
        EmbeddingStore<TextSegment> b = service.createEmbeddingStore("kb_b", 1536);
        // 访问 a 使其成为最近使用项，随后写入 kb_c 应淘汰 kb_b
        service.createEmbeddingStore("kb_a", 1536);
        service.createEmbeddingStore("kb_c", 1536);

        assertThat(service.createEmbeddingStore("kb_a", 1536)).isSameAs(a);
        // kb_b 已被淘汰：重建后应得到不同实例
        assertThat(service.createEmbeddingStore("kb_b", 1536)).isNotSameAs(b);
    }

    @Test
    @DisplayName("删表后失效该表全部维度句柄")
    void dropCollectionInvalidatesHandles() throws Exception {
        EmbeddingStore<TextSegment> before = service.createEmbeddingStore("kb_a", 1536);
        service.dropCollection("kb_a");
        EmbeddingStore<TextSegment> after = service.createEmbeddingStore("kb_a", 1536);

        assertThat(after).isNotSameAs(before);
        verify(statement).execute("DROP TABLE IF EXISTS kb_a");
    }

    @Test
    @DisplayName("ensureCollection 幂等创建 HNSW 索引，同一表每进程只执行一次 DDL")
    void ensureCollectionCreatesHnswIndexOnce() throws Exception {
        service.ensureCollection("kb_a", 1536);
        service.ensureCollection("kb_a", 1536);

        verify(statement, times(1)).execute(argThat(sql ->
                sql.contains("USING hnsw (embedding vector_cosine_ops)")
                        && sql.contains("m = 16")
                        && sql.contains("ef_construction = 64")));
    }

    @Test
    @DisplayName("关闭 use-hnsw-index 时不创建 HNSW 索引")
    void hnswDisabledSkipsDdl() throws Exception {
        props.setUseHnswIndex(false);
        service.ensureCollection("kb_a", 1536);

        verify(statement, never()).execute(argThat(sql -> sql.contains("USING hnsw")));
    }
}
