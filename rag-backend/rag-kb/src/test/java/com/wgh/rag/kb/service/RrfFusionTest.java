package com.wgh.rag.kb.service;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * RRF 融合单测：跨路去重、排序、limit、原始分数保留
 */
class RrfFusionTest {

    private EmbeddingMatch<TextSegment> m(String id, double score, String text) {
        return new EmbeddingMatch<>(score, id, null, TextSegment.from(text));
    }

    @Test
    void fuses_and_dedupes_across_lists_by_embedding_id() {
        // 向量路：A > B；关键词路：B > C（B 两路命中应合并且排名提升）
        List<EmbeddingMatch<TextSegment>> vector = List.of(m("A", 0.9, "甲"), m("B", 0.8, "乙"));
        List<EmbeddingMatch<TextSegment>> keyword = List.of(m("B", 0.6, "乙"), m("C", 0.5, "丙"));

        List<EmbeddingMatch<TextSegment>> fused = RrfFusion.fuse(List.of(vector, keyword), 10);

        // B: 1/61 + 1/61 ≈ 0.0328 最高；A: 1/61；C: 1/62 最低
        assertThat(fused).extracting(x -> x.embeddingId()).containsExactly("B", "A", "C");
        // 保留原始分数（向量路 0.8 高于关键词 0.6）
        assertThat(fused.get(0).score()).isEqualTo(0.8);
    }

    @Test
    void respects_limit() {
        List<EmbeddingMatch<TextSegment>> vector = List.of(m("A", 0.9, "甲"), m("B", 0.8, "乙"), m("C", 0.7, "丙"));
        List<EmbeddingMatch<TextSegment>> fused = RrfFusion.fuse(List.of(vector, List.of()), 2);
        assertThat(fused).hasSize(2);
        assertThat(fused).extracting(EmbeddingMatch::embeddingId).containsExactly("A", "B");
    }

    @Test
    void empty_keyword_list_degrades_to_vector_order() {
        List<EmbeddingMatch<TextSegment>> vector = List.of(m("A", 0.9, "甲"), m("B", 0.8, "乙"));
        List<EmbeddingMatch<TextSegment>> fused = RrfFusion.fuse(List.of(vector, List.of()), 10);
        assertThat(fused).extracting(EmbeddingMatch::embeddingId).containsExactly("A", "B");
    }

}
