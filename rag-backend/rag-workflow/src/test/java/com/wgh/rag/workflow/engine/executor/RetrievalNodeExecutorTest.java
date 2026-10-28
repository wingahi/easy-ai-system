package com.wgh.rag.workflow.engine.executor;

import com.wgh.rag.kb.service.RagRetrieveService;
import com.wgh.rag.workflow.dto.GraphNode;
import com.wgh.rag.workflow.dto.NodeResult;
import com.wgh.rag.workflow.dto.RunContext;
import com.wgh.rag.workflow.engine.VariableResolver;
import com.wgh.rag.workflow.entity.WorkflowRun;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RETRIEVAL 节点执行器测试 —— kbIds 校验、query 回退、topK 截断、sources 组装。
 */
@ExtendWith(MockitoExtension.class)
class RetrievalNodeExecutorTest {

    @Mock
    private RagRetrieveService ragRetrieveService;

    private final VariableResolver variableResolver = new VariableResolver();

    private RetrievalNodeExecutor executor() {
        return new RetrievalNodeExecutor(variableResolver, ragRetrieveService);
    }

    /** 未配置 kbIds 时抛出"知识库节点未选择知识库"异常 */
    @Test
    void execute_emptyKbIds_throws() {
        GraphNode node = node(Map.of("query", "q"));

        assertThatThrownBy(() -> executor().execute(context(node, new RunContext())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("知识库节点未选择知识库");
    }

    /** query 为空时回退使用 vars.input 作为检索查询 */
    @Test
    void execute_blankQuery_fallsBackToInput() {
        when(ragRetrieveService.searchMultiple(anyList(), anyString())).thenReturn(List.of());
        RunContext ctx = new RunContext();
        ctx.getVars().put("input", "默认查询");
        GraphNode node = node(Map.of("kbIds", List.of(1, 2)));

        executor().execute(context(node, ctx));

        verify(ragRetrieveService).searchMultiple(eq(List.of(1L, 2L)), eq("默认查询"));
    }

    /** 逗号字符串形式的 kbIds 被解析为 Long 列表 */
    @Test
    void execute_kbIdsAsCommaString_parses() {
        when(ragRetrieveService.searchMultiple(anyList(), anyString())).thenReturn(List.of());
        GraphNode node = node(Map.of("kbIds", "3, 4", "query", "q"));

        executor().execute(context(node, new RunContext()));

        verify(ragRetrieveService).searchMultiple(eq(List.of(3L, 4L)), eq("q"));
    }

    /** topK 截断检索结果，拼接片段文本并组装 sources 元数据 */
    @Test
    void execute_topKTruncatesAndBuildsSources() {
        when(ragRetrieveService.searchMultiple(anyList(), anyString())).thenReturn(List.of(
                match(0.9, "a.pdf", 1, "内容A"),
                match(0.8, "b.pdf", null, "内容B"),
                match(0.7, "c.pdf", 3, "内容C")));
        GraphNode node = node(Map.of("kbIds", List.of(1), "query", "q", "topK", 2));

        NodeResult result = executor().execute(context(node, new RunContext()));

        assertThat(result.getOutput().get("count")).isEqualTo(2);
        assertThat((String) result.getOutput().get("text"))
                .contains("【片段 1】a.pdf#1\n内容A")
                .contains("【片段 2】b.pdf\n内容B")
                .doesNotContain("内容C");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sources = (List<Map<String, Object>>) result.getOutput().get("sources");
        assertThat(sources).hasSize(2);
        assertThat(sources.get(0)).containsEntry("fileName", "a.pdf").containsEntry("chunkIndex", 1);
        assertThat(sources.get(1)).containsEntry("fileName", "b.pdf").containsEntry("chunkIndex", null);
    }

    /** 检索返回 null 时输出空文本且 count 为 0 */
    @Test
    void execute_nullMatches_returnsEmpty() {
        when(ragRetrieveService.searchMultiple(anyList(), anyString())).thenReturn(null);
        GraphNode node = node(Map.of("kbIds", List.of(1), "query", "q"));

        NodeResult result = executor().execute(context(node, new RunContext()));

        assertThat(result.getOutput()).containsEntry("text", "").containsEntry("count", 0);
    }

    private EmbeddingMatch<TextSegment> match(double score, String fileName, Integer chunkIndex, String content) {
        Metadata md = new Metadata();
        if (fileName != null) md.put("fileName", fileName);
        if (chunkIndex != null) md.put("chunkIndex", chunkIndex);
        return new EmbeddingMatch<>(score, "id-" + content, null, TextSegment.from(content, md));
    }

    private NodeExecutionContext context(GraphNode node, RunContext ctx) {
        return new NodeExecutionContext(node, ctx, new WorkflowRun(), 1);
    }

    private GraphNode node(Map<String, Object> config) {
        GraphNode n = new GraphNode();
        n.setId("r");
        n.setType("RETRIEVAL");
        n.setConfig(new LinkedHashMap<>(config));
        return n;
    }
}
