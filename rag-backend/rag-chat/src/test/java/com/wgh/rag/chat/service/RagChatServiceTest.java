package com.wgh.rag.chat.service;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T03 chunk 级引用契约：来源字段完整、同文档去重保留最高分、上下文 [Sn] 编号与来源卡片顺序一致。
 */
class RagChatServiceTest {

    private final RagChatService service = new RagChatService(null, null, null, null, null,
            null, null, null, null, null);

    @Test
    @DisplayName("来源列表包含 chunk 级字段并按文件去重保留最高分")
    void buildSourcesList_containsChunkFieldsAndDedupesByFile() {
        TextSegment best = segment(101L, "manual.pdf", 2, 120, 260, "高分段原文");
        TextSegment lower = segment(101L, "manual.pdf", 5, 300, 420, "低分段原文");
        TextSegment other = segment(202L, "guide.pdf", 0, 0, 80, "另一份文档");

        List<Map<String, Object>> sources = service.buildSourcesList(List.of(
                match(0.91, best), match(0.72, lower), match(0.66, other)));

        assertThat(sources).hasSize(2);
        assertThat(sources.get(0))
                .containsEntry("fileId", 101L)
                .containsEntry("fileName", "manual.pdf")
                .containsEntry("chunkIndex", 2)
                .containsEntry("charStart", 120)
                .containsEntry("charEnd", 260)
                .containsEntry("snippet", "高分段原文")
                .containsEntry("score", 0.91);
        assertThat(sources.get(1)).containsEntry("fileId", 202L);
    }

    @Test
    @DisplayName("上下文按文档编号 [S1]/[S2]，同文件多段共用同一编号")
    void extractRagContext_numbersByDocument() {
        TextSegment first = segment(101L, "manual.pdf", 0, 0, 10, "第一段");
        TextSegment second = segment(101L, "manual.pdf", 1, 10, 20, "第二段");
        TextSegment other = segment(202L, "guide.pdf", 0, 0, 10, "另一份");

        String context = service.extractRagContext(List.of(
                match(0.9, first), match(0.8, second), match(0.7, other)));

        assertThat(context).startsWith("[S1] 【来源: 手册库 - manual.pdf】")
                .contains("[S2] 【来源: 手册库 - guide.pdf】")
                .doesNotContain("[S3]");
    }

    private TextSegment segment(Long fileId, String fileName, int chunkIndex,
                                int charStart, int charEnd, String snippet) {
        Metadata metadata = Metadata.from(Map.of(
                "kbId", 7L,
                "kbName", "手册库",
                "fileId", fileId,
                "fileName", fileName,
                "chunkIndex", chunkIndex,
                "charStart", charStart,
                "charEnd", charEnd,
                "snippet", snippet,
                "pageNo", 3));
        return TextSegment.from(snippet, metadata);
    }

    private EmbeddingMatch<TextSegment> match(double score, TextSegment segment) {
        return new EmbeddingMatch<>(score, "id-" + score, null, segment);
    }
}
