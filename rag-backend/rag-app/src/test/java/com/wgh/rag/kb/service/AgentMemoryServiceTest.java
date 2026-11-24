package com.wgh.rag.kb.service;

import com.wgh.rag.config.property.RagProperties;
import com.wgh.rag.kb.entity.AgentMemory;
import com.wgh.rag.kb.mapper.AgentMemoryMapper;
import com.wgh.rag.kb.port.VectorStorePort;
import com.wgh.rag.kb.port.vector.VectorMatch;
import com.wgh.rag.kb.port.vector.VectorRecord;
import com.wgh.rag.kb.port.vector.VectorScoreType;
import com.wgh.rag.llm.entity.EmbeddingModelConfig;
import com.wgh.rag.llm.factory.ModelCacheManager;
import com.wgh.rag.llm.mapper.EmbeddingModelMapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 检索式记忆服务测试：写入去重、召回过滤、Token 预算与降级。
 */
@DisplayName("检索式记忆")
@SuppressWarnings("unchecked")
class AgentMemoryServiceTest {

    private AgentMemoryMapper memoryMapper;
    private EmbeddingModelMapper embeddingModelMapper;
    private VectorStorePort vectorStorePort;
    private ModelCacheManager modelCacheManager;
    private AgentMemoryService service;

    @BeforeEach
    void setUp() {
        memoryMapper = Mockito.mock(AgentMemoryMapper.class);
        embeddingModelMapper = Mockito.mock(EmbeddingModelMapper.class);
        vectorStorePort = Mockito.mock(VectorStorePort.class);
        modelCacheManager = Mockito.mock(ModelCacheManager.class);

        EmbeddingModelConfig config = new EmbeddingModelConfig();
        config.setId(1L);
        config.setDimension(8);
        when(embeddingModelMapper.selectList(any())).thenReturn(List.of(config));

        EmbeddingModel embeddingModel = Mockito.mock(EmbeddingModel.class);
        when(embeddingModel.embed(anyString())).thenReturn(Response.from(Embedding.from(new float[]{0.1f})));
        when(modelCacheManager.getEmbeddingModel(1L)).thenReturn(embeddingModel);
        when(vectorStorePort.upsert(anyString(), anyList(), anyInt())).thenReturn(List.of("emb-1"));

        service = new AgentMemoryService(memoryMapper, embeddingModelMapper, vectorStorePort,
                modelCacheManager, new RagProperties());
    }

    private static VectorMatch match(double score, String id, String text) {
        return new VectorMatch(id, score, VectorScoreType.COSINE, text, Map.of());
    }

    @Test
    @DisplayName("写入 - 同作用域重复正文跳过")
    void rememberSkipsDuplicate() {
        when(memoryMapper.selectCount(any())).thenReturn(1L);

        service.remember(AgentMemoryService.ScopeType.SESSION, "s1", null, "SUMMARY", "同一条记忆", null);

        verify(vectorStorePort, never()).upsert(anyString(), anyList(), anyInt());
        verify(memoryMapper, never()).insert(any(AgentMemory.class));
    }

    @Test
    @DisplayName("写入 - 新正文写入向量与业务表")
    void rememberWritesVectorAndRow() {
        when(memoryMapper.selectCount(any())).thenReturn(0L);

        service.remember(AgentMemoryService.ScopeType.SESSION, "s1", "a1", "SUMMARY", "用户偏好：按季度对比", 9L);

        ArgumentCaptor<List<VectorRecord>> records = ArgumentCaptor.forClass(List.class);
        verify(vectorStorePort).upsert(eq(AgentMemoryService.VEC_TABLE), records.capture(), eq(8));
        VectorRecord record = records.getValue().get(0);
        assertThat(record.metadata().get("scopeKey")).isEqualTo("SESSION:s1");
        assertThat(record.metadata().get("agentId")).isEqualTo("a1");

        ArgumentCaptor<AgentMemory> row = ArgumentCaptor.forClass(AgentMemory.class);
        verify(memoryMapper).insert(row.capture());
        assertThat(row.getValue().getContent()).isEqualTo("用户偏好：按季度对比");
        assertThat(row.getValue().getContentHash()).isEqualTo(AgentMemoryService.sha256("用户偏好：按季度对比"));
        assertThat(row.getValue().getTokenCount()).isPositive();
        assertThat(row.getValue().getSourceMessageId()).isEqualTo(9L);
        assertThat(row.getValue().getEmbeddingId()).isEqualTo("emb-1");
    }

    @Test
    @DisplayName("写入 - 关闭开关时直接跳过")
    void rememberDisabled() {
        RagProperties props = new RagProperties();
        props.getMemory().setEnabled(false);
        service = new AgentMemoryService(memoryMapper, embeddingModelMapper, vectorStorePort,
                modelCacheManager, props);

        service.remember(AgentMemoryService.ScopeType.SESSION, "s1", null, "SUMMARY", "内容", null);

        verify(vectorStorePort, never()).upsert(anyString(), anyList(), anyInt());
        verify(memoryMapper, never()).insert(any(AgentMemory.class));
    }

    @Test
    @DisplayName("召回 - scopeKey 过滤与相似度阈值下发到向量检索")
    void recallPassesScopeAndThreshold() {
        when(vectorStorePort.search(eq(AgentMemoryService.VEC_TABLE), any(), anyInt(), anyDouble(), any()))
                .thenReturn(List.of(match(0.9, "e1", "记忆一")));
        when(memoryMapper.selectList(any())).thenReturn(List.of(row("e1")));

        List<AgentMemoryService.MemoryHit> hits = service.recall(
                AgentMemoryService.ScopeType.SESSION, "s1", "查询");

        verify(vectorStorePort).search(eq(AgentMemoryService.VEC_TABLE), any(), eq(5), eq(0.6), any());
        assertThat(hits).extracting(AgentMemoryService.MemoryHit::content).containsExactly("记忆一");
    }

    @Test
    @DisplayName("召回 - 超出 Token 预算的靠后条目被丢弃")
    void recallRespectsTokenBudget() {
        String longText = "记".repeat(400); // 约 400 tokens
        when(vectorStorePort.search(eq(AgentMemoryService.VEC_TABLE), any(), anyInt(), anyDouble(), any()))
                .thenReturn(List.of(match(0.9, "e1", longText), match(0.8, "e2", longText)));
        when(memoryMapper.selectList(any())).thenReturn(List.of(row("e1"), row("e2")));

        List<AgentMemoryService.MemoryHit> hits = service.recall(
                AgentMemoryService.ScopeType.SESSION, "s1", "查询");

        assertThat(hits).hasSize(1);
    }

    @Test
    @DisplayName("召回 - 已逻辑删除的记忆被过滤")
    void recallSkipsDeleted() {
        when(vectorStorePort.search(eq(AgentMemoryService.VEC_TABLE), any(), anyInt(), anyDouble(), any()))
                .thenReturn(List.of(match(0.9, "e1", "有效"), match(0.8, "e2", "已删除")));
        when(memoryMapper.selectList(any())).thenReturn(List.of(row("e1")));

        List<AgentMemoryService.MemoryHit> hits = service.recall(
                AgentMemoryService.ScopeType.SESSION, "s1", "查询");

        assertThat(hits).extracting(AgentMemoryService.MemoryHit::content).containsExactly("有效");
    }

    @Test
    @DisplayName("召回 - 向量检索异常时降级为空")
    void recallDegradesOnError() {
        when(vectorStorePort.search(eq(AgentMemoryService.VEC_TABLE), any(), anyInt(), anyDouble(), any()))
                .thenThrow(new RuntimeException("pgvector down"));

        assertThat(service.recall(AgentMemoryService.ScopeType.SESSION, "s1", "查询")).isEmpty();
    }

    @Test
    @DisplayName("召回 - 未配置 embedding 模型时为空")
    void recallWithoutEmbeddingModel() {
        when(embeddingModelMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.recall(AgentMemoryService.ScopeType.SESSION, "s1", "查询")).isEmpty();
        verify(vectorStorePort, never()).search(anyString(), any(), anyInt(), anyDouble(), any());
    }

    private static AgentMemory row(String embeddingId) {
        AgentMemory row = new AgentMemory();
        row.setEmbeddingId(embeddingId);
        row.setDeleted(0);
        return row;
    }
}
