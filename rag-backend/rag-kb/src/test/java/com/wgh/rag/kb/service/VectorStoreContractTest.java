package com.wgh.rag.kb.service;

import com.wgh.rag.kb.port.KeywordFilter;
import com.wgh.rag.kb.port.KeywordHit;
import com.wgh.rag.kb.port.vector.CollectionInfo;
import com.wgh.rag.kb.port.vector.ScanPage;
import com.wgh.rag.kb.port.vector.VectorFilter;
import com.wgh.rag.kb.port.vector.VectorMatch;
import com.wgh.rag.kb.port.vector.VectorRange;
import com.wgh.rag.kb.port.vector.VectorRecord;
import com.wgh.rag.kb.port.vector.VectorScoreType;
import com.wgh.rag.kb.service.memory.InMemoryKeywordSearchService;
import com.wgh.rag.kb.service.memory.InMemoryVectorStoreService;
import com.wgh.rag.llm.factory.ModelCacheManager;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端口契约单测（内存后端）：过滤、租户、批量、游标、分数类型。
 * <p>内存后端与 pgvector 实现同一契约，用于验证换后端不改业务代码。</p>
 */
class VectorStoreContractTest {

    private final InMemoryVectorStoreService vectors =
            new InMemoryVectorStoreService(Mockito.mock(ModelCacheManager.class));

    private void seed() {
        vectors.upsert("c1", List.of(
                new VectorRecord("a", "苹果", Map.of("tenantId", "1", "fileId", "10", "year", 2024),
                        new float[]{1f, 0f}),
                new VectorRecord("b", "香蕉", Map.of("tenantId", "1", "fileId", "11", "year", 2023),
                        new float[]{0f, 1f}),
                new VectorRecord("c", "樱桃", Map.of("tenantId", "2", "fileId", "10", "year", 2024),
                        new float[]{1f, 0f})), 2);
    }

    @Test
    @DisplayName("向量检索：租户 + 等值 + 范围过滤，分数声明为余弦")
    void searchAppliesTenantEqualsAndRangeFilters() {
        seed();
        List<VectorMatch> hits = vectors.search("c1", new float[]{1f, 0f}, 10, 0.0,
                new VectorFilter("1", Map.of("fileId", "10"), Map.of("year", VectorRange.atLeast(2024))));

        assertThat(hits).extracting(VectorMatch::id).containsExactly("a");
        assertThat(hits.get(0).scoreType()).isEqualTo(VectorScoreType.COSINE);
    }

    @Test
    @DisplayName("向量检索：minScore 生效，低于阈值的命中被丢弃")
    void searchAppliesMinScore() {
        seed();
        List<VectorMatch> hits = vectors.search("c1", new float[]{1f, 0f}, 10, 0.5, VectorFilter.none());
        assertThat(hits).extracting(VectorMatch::id).containsExactlyInAnyOrder("a", "c");
    }

    @Test
    @DisplayName("游标扫描：按 ID 分页，nextCursor 为空表示到末尾")
    void scanPaginatesByCursor() {
        seed();
        ScanPage first = vectors.scan("c1", null, 2);
        assertThat(first.records()).hasSize(2);
        assertThat(first.nextCursor()).isNotNull();

        ScanPage second = vectors.scan("c1", first.nextCursor(), 2);
        assertThat(second.records()).hasSize(1);
        assertThat(second.nextCursor()).isNull();
    }

    @Test
    @DisplayName("按文件删除：只清理该文件记录，其余保留")
    void deleteByFileIdRemovesOnlyTargetFile() {
        seed();
        vectors.deleteByFileId("c1", 10L);
        assertThat(vectors.countVectors("c1")).isEqualTo(1);
        assertThat(vectors.scan("c1", null, 10).records()).extracting(VectorRecord::id).containsExactly("b");
    }

    @Test
    @DisplayName("集合元数据：后端名/维度/行数")
    void describeReturnsCollectionMetadata() {
        seed();
        CollectionInfo info = vectors.describe("c1");
        assertThat(info.backend()).isEqualTo("memory");
        assertThat(info.dimension()).isEqualTo(2);
        assertThat(info.count()).isEqualTo(3);
    }

    @Test
    @DisplayName("关键词检索：租户过滤生效，分数声明为 KEYWORD")
    void keywordSearchAppliesTenantFilter() {
        InMemoryKeywordSearchService keywords = new InMemoryKeywordSearchService();
        keywords.index("c1", "a", TextSegment.from("苹果 香蕉", dev.langchain4j.data.document.Metadata.from(
                Map.of("tenantId", "1"))));
        keywords.index("c1", "c", TextSegment.from("苹果 樱桃", dev.langchain4j.data.document.Metadata.from(
                Map.of("tenantId", "2"))));

        List<KeywordHit> hits = keywords.search("c1", "苹果", 10, new KeywordFilter("1", Map.of()));

        assertThat(hits).extracting(KeywordHit::id).containsExactly("a");
        assertThat(hits.get(0).scoreType()).isEqualTo(VectorScoreType.KEYWORD);
    }
}
