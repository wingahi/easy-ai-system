package com.wgh.rag.kb.parser;

import com.wgh.rag.config.property.RagProperties;
import com.wgh.rag.kb.splitter.ChunkOutput;
import com.wgh.rag.kb.splitter.SplitContext;
import com.wgh.rag.kb.splitter.TextSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 链路用例：上传（MockMultipartFile）→ 解析（新增 HTML 格式）→ 切片 → 向量化 → 检索命中。
 */
@DisplayName("文档上传到检索命中链路测试")
class DocumentIngestChainTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("链路 - HTML 上传后解析、切片、向量入库并检索命中")
    void uploadParseSplitEmbedAndRetrieve() throws Exception {
        // 1. 模拟上传：MultipartFile 落盘
        String html = """
                <html><head><title>采购制度</title></head><body>
                <h1>采购审批</h1>
                <p>单笔采购超过 5 万元需要部门负责人审批。</p>
                <p>供应商需在系统内完成准入登记。</p>
                </body></html>
                """;
        MockMultipartFile upload = new MockMultipartFile(
                "file", "采购制度.html", "text/html", html.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Path stored = tempDir.resolve(upload.getOriginalFilename());
        Files.copy(upload.getInputStream(), stored);

        // 2. 解析（分发器识别 html 扩展名）
        DocumentParserDispatcher dispatcher = new DocumentParserDispatcher(List.of(new HtmlParser()));
        ParsedDocument parsed = dispatcher.parseDocument(stored.toFile(), "html");
        assertThat(parsed.title()).isEqualTo("采购制度");
        assertThat(parsed.paragraphs()).anyMatch(p -> p.contains("5 万元"));

        // 3. 切片
        TextSplitter splitter = new TextSplitter(new RagProperties());
        List<ChunkOutput> chunks = splitter.splitDetailed(
                parsed.paragraphs(), TextSplitter.Strategy.RECURSIVE, null, new SplitContext(null));
        assertThat(chunks).isNotEmpty();

        // 4. 向量化并写入内存向量库（等价 pgvector 的 EmbeddingStore 抽象）
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        Embedding vector = Embedding.from(new float[]{1f, 0f, 0f});
        when(embeddingModel.embed(anyString())).thenReturn(Response.from(vector));
        InMemoryEmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();

        for (int i = 0; i < chunks.size(); i++) {
            Metadata metadata = Metadata.from(java.util.Map.of(
                    "fileId", 1L, "fileName", "采购制度.html", "chunkIndex", i));
            TextSegment segment = TextSegment.from(chunks.get(i).text(), metadata);
            store.add(embeddingModel.embed(segment.text()).content(), segment);
        }

        // 5. 检索命中（用相同向量空间模拟问题 embedding）
        List<EmbeddingMatch<TextSegment>> matches = store.search(EmbeddingSearchRequest.builder()
                .queryEmbedding(embeddingModel.embed("采购审批金额").content())
                .maxResults(3)
                .build()).matches();

        assertThat(matches).isNotEmpty();
        assertThat(matches).anyMatch(m -> m.embedded().text().contains("5 万元")
                || m.embedded().text().contains("审批"));
    }
}
