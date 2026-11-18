package com.wgh.rag.chat.service;

import com.wgh.rag.agent.dto.AgentStepResult.RagSourceRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Agent 步骤内检索来源 → 引用卡片 的转换口径测试。
 */
@DisplayName("Agent 引用来源卡片")
class AgentSourceCardsTest {

    /** 同一文档的多段合并为一张卡片，保留最高分；卡片顺序为文档首次出现顺序。 */
    @Test
    @DisplayName("同文档去重 - 保留最高分与首次出现顺序")
    void buildSources_sameFile_keepsHighestScore() {
        List<RagSourceRecord> records = List.of(
                RagSourceRecord.builder().kbId(1L).kbName("kb1").fileId(10L).fileName("a.pdf")
                        .chunkIndex(0).score(0.6).snippet("s0").build(),
                RagSourceRecord.builder().kbId(1L).kbName("kb1").fileId(11L).fileName("b.pdf")
                        .chunkIndex(2).score(0.7).build(),
                RagSourceRecord.builder().kbId(1L).kbName("kb1").fileId(10L).fileName("a.pdf")
                        .chunkIndex(3).score(0.9).snippet("s3").build());

        List<Map<String, Object>> sources = RagChatService.buildSourcesListFromRecords(records);

        assertThat(sources).hasSize(2);
        assertThat(sources.get(0)).containsEntry("fileName", "a.pdf")
                .containsEntry("score", 0.9)
                .containsEntry("chunkIndex", 3)
                .containsEntry("snippet", "s3");
        assertThat(sources.get(1)).containsEntry("fileName", "b.pdf")
                .containsEntry("score", 0.7);
    }

    /** 文件名缺失时按 fileId 归并，保证同一文件的不同分段仍去重。 */
    @Test
    @DisplayName("文件名缺失 - 回退 fileId 去重")
    void buildSources_missingFileName_fallsBackToFileId() {
        List<RagSourceRecord> records = List.of(
                RagSourceRecord.builder().fileId(42L).chunkIndex(0).score(0.5).build(),
                RagSourceRecord.builder().fileId(42L).chunkIndex(1).score(0.8).build());

        List<Map<String, Object>> sources = RagChatService.buildSourcesListFromRecords(records);

        assertThat(sources).hasSize(1);
        assertThat(sources.get(0)).containsEntry("fileId", 42L).containsEntry("score", 0.8);
    }

    /** Agent 计划未安排检索时来源为空，不再回落到窗口级预检索结果。 */
    @Test
    @DisplayName("无步骤来源 - 返回空列表")
    void buildSources_noRecords_returnsEmpty() {
        assertThat(RagChatService.buildSourcesListFromRecords(List.of())).isEmpty();
        assertThat(RagChatService.buildSourcesListFromRecords(null)).isEmpty();
    }
}
