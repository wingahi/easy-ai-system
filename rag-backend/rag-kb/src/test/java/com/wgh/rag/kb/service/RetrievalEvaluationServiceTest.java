package com.wgh.rag.kb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.kb.entity.KbGroundTruth;
import com.wgh.rag.kb.mapper.KbGroundTruthMapper;
import com.wgh.rag.kb.mapper.KnowledgeBaseMapper;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * 检索评测单测：Recall@K 分母为标注相关集合、逐条聚合、无标注不可计算
 */
@DisplayName("检索评测指标测试")
class RetrievalEvaluationServiceTest {

    private final KbGroundTruthMapper groundTruthMapper = Mockito.mock(KbGroundTruthMapper.class);
    private final KnowledgeBaseMapper knowledgeBaseMapper = Mockito.mock(KnowledgeBaseMapper.class);
    private final RagRetrieveService retrieveService = Mockito.mock(RagRetrieveService.class);
    private final DataSource dataSource = Mockito.mock(DataSource.class);

    private RetrievalEvaluationService service;

    @BeforeEach
    void setUp() {
        service = new RetrievalEvaluationService(groundTruthMapper, knowledgeBaseMapper,
                retrieveService, dataSource, new ObjectMapper());
    }

    private KbGroundTruth gt(String query, String relevantJson) {
        KbGroundTruth gt = new KbGroundTruth();
        gt.setKnowledgeBaseId(1L);
        gt.setQueryText(query);
        gt.setRelevantChunks(relevantJson);
        return gt;
    }

    private EmbeddingMatch<TextSegment> match(String fileId, String chunkIndex) {
        Metadata metadata = Metadata.from(Map.of("fileId", fileId, "chunkIndex", chunkIndex));
        return new EmbeddingMatch<>(0.8, fileId + ":" + chunkIndex, null, TextSegment.from("chunk", metadata));
    }

    @Test
    @DisplayName("Recall@K - 分母为标注相关集合（2 个相关命中 1 个 → 0.5，而不是 1/5）")
    void recallUsesRelevantSetAsDenominator() {
        when(groundTruthMapper.selectList(any()))
                .thenReturn(List.of(gt("q1", "[\"1:0\",\"1:1\"]")));
        when(retrieveService.searchMultiple(eq(List.of(1L)), eq("q1"), any(RetrievalOptions.class)))
                .thenReturn(new RetrieveResult(List.of(match("1", "0"), match("1", "7")), false, 0));

        Map<String, Object> result = service.evaluate(1L, new RetrievalOptions(null, false, null, null, 2));

        assertThat(result.get("queryCount")).isEqualTo(1);
        assertThat(result.get("topK")).isEqualTo(2);
        assertThat(result.get("recallAtK")).isEqualTo(0.5);
        assertThat(result.get("precisionAtK")).isEqualTo(0.5);
        assertThat(result.get("queryHitRate")).isEqualTo(1.0);
        @SuppressWarnings("unchecked")
        Map<String, Object> detail = ((List<Map<String, Object>>) result.get("details")).get(0);
        assertThat(detail.get("relevantCount")).isEqualTo(2);
        assertThat(detail.get("hitCount")).isEqualTo(1L);
        assertThat(detail.get("hit")).isEqualTo(true);
    }

    @Test
    @DisplayName("Recall@K - 未命中任何标注 chunk 时为 0")
    void recallZeroWhenNoRelevantRetrieved() {
        when(groundTruthMapper.selectList(any()))
                .thenReturn(List.of(gt("q2", "[\"2:3\"]")));
        when(retrieveService.searchMultiple(eq(List.of(1L)), eq("q2"), any(RetrievalOptions.class)))
                .thenReturn(new RetrieveResult(List.of(match("2", "9")), false, 0));

        Map<String, Object> result = service.evaluate(1L, new RetrievalOptions(null, false, null, null, 3));

        assertThat(result.get("recallAtK")).isEqualTo(0.0);
        assertThat(result.get("queryHitRate")).isEqualTo(0.0);
    }

    @Test
    @DisplayName("无标注数据时 Recall@K 不可计算，抛出业务异常")
    void evaluateWithoutGroundTruthFails() {
        when(groundTruthMapper.selectList(any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.evaluate(1L, RetrievalOptions.defaults()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("暂无标注数据");
    }

    @Test
    @DisplayName("chunk 键解析 - 缺少 fileId/chunkIndex 的元数据不可用于评测")
    void chunkKeyRequiresMetadata() {
        EmbeddingMatch<TextSegment> noMeta = new EmbeddingMatch<>(0.5, "id", null, TextSegment.from("c"));
        assertThat(RetrievalEvaluationService.chunkKey(noMeta)).isNull();
        assertThat(RetrievalEvaluationService.chunkKey(match("3", "4"))).isEqualTo("3:4");
    }
}
