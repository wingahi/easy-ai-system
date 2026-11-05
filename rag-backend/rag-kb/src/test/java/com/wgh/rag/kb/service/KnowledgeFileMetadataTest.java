package com.wgh.rag.kb.service;

import com.wgh.rag.kb.entity.KnowledgeBase;
import com.wgh.rag.kb.entity.KnowledgeFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 写入侧元数据契约：chunk metadata 必须携带 kbName（读取侧依赖该字段展示来源）。
 */
class KnowledgeFileMetadataTest {

    @Test
    @DisplayName("写入元数据包含 kbName 及来源字段")
    void buildChunkMetadata_containsKbName() {
        KnowledgeFile file = new KnowledgeFile();
        file.setId(11L);
        file.setFileName("manual.pdf");
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(3L);
        kb.setName("产品手册库");

        KnowledgeFileService service = new KnowledgeFileService(null, null, null, null, null,
                null, null, null, null);
        Map<String, Object> meta = service.buildChunkMetadata(file, kb, 2, Map.of("headingPath", "1 > 1.1"));

        assertThat(meta).containsEntry("kbName", "产品手册库")
                .containsEntry("kbId", 3L)
                .containsEntry("fileId", 11L)
                .containsEntry("fileName", "manual.pdf")
                .containsEntry("chunkIndex", 2)
                .containsEntry("headingPath", "1 > 1.1");
    }
}
