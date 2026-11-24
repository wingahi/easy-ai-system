package com.wgh.rag.kb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.kb.port.KeywordFilter;
import com.wgh.rag.kb.port.KeywordHit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 关键词召回 SQL 契约：必须使用 word_similarity(query, text)（短查询 vs 长 chunk），
 * 参数顺序与操作符方向不得回退到 similarity(text, query)；查询失败与无命中要可区分。
 */
class KeywordSearchServiceTest {

    private final DataSource dataSource = Mockito.mock(DataSource.class);
    private final Connection connection = Mockito.mock(Connection.class);
    private final Statement statement = Mockito.mock(Statement.class);
    private final PreparedStatement preparedStatement = Mockito.mock(PreparedStatement.class);
    private final ResultSet resultSet = Mockito.mock(ResultSet.class);

    private KeywordSearchService service;

    @BeforeEach
    void setUp() throws SQLException {
        service = new KeywordSearchService(dataSource, new ObjectMapper());
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
    }

    @Test
    @DisplayName("长 chunk + 短 query：word_similarity 路径返回非空结果")
    void longChunkShortQuery_returnsMatches() throws SQLException {
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getString("text")).thenReturn("很长的知识库段落……");
        when(resultSet.getString("metadata")).thenReturn(null);
        when(resultSet.getString("embedding_id")).thenReturn("id-1");
        when(resultSet.getDouble("score")).thenReturn(0.72);

        List<KeywordHit> hits = service.search("wgh_kb_123", "混合检索", 10, KeywordFilter.none());

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).score()).isEqualTo(0.72);
        assertThat(hits.get(0).text()).isEqualTo("很长的知识库段落……");
        assertThat(hits.get(0).highlights()).isEmpty();
    }

    @Test
    @DisplayName("SQL 使用 word_similarity(?, text) 且参数顺序为 query/query/阈值/topN")
    void sqlUsesWordSimilarityWithExpectedParameterOrder() throws SQLException {
        when(resultSet.next()).thenReturn(false);

        service.search("wgh_kb_123", "混合检索", 10, KeywordFilter.none());

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sqlCaptor.capture());
        String sql = sqlCaptor.getValue();

        assertThat(sql).contains("word_similarity(?, text)")
                .doesNotContain("similarity(text, ?)")
                .doesNotContain("text % ?");
        // 1/2 为 query（SELECT 分数与 WHERE 谓词），3 为阈值，4 为 topN
        verify(preparedStatement).setObject(1, "混合检索");
        verify(preparedStatement).setObject(2, "混合检索");
        verify(preparedStatement).setObject(3, 0.5);
        verify(preparedStatement).setObject(4, 10);
    }

    @Test
    @DisplayName("过滤条件翻译为 metadata JSONB 谓词，且不改变主谓词顺序")
    void filterIsTranslatedIntoMetadataPredicate() throws SQLException {
        when(resultSet.next()).thenReturn(false);

        service.search("wgh_kb_123", "混合检索", 10, new KeywordFilter("7", Map.of("fileId", "42")));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sqlCaptor.capture());
        assertThat(sqlCaptor.getValue()).contains("metadata->>'tenantId' = ?")
                .contains("metadata->>'fileId' = ?");
        verify(preparedStatement).setObject(4, "7");
        verify(preparedStatement).setObject(5, "42");
        verify(preparedStatement).setObject(6, 10);
    }

    @Test
    @DisplayName("查询异常：返回空列表且不向上抛出（调用方降级纯向量路）")
    void queryFailure_returnsEmptyWithoutThrow() throws SQLException {
        when(preparedStatement.executeQuery()).thenThrow(new SQLException("function word_similarity does not exist"));

        List<KeywordHit> hits = service.search("wgh_kb_123", "混合检索", 10, KeywordFilter.none());

        assertThat(hits).isEmpty();
    }

    @Test
    @DisplayName("无命中：正常空结果，不触发 SQL 异常分支")
    void noHit_returnsEmpty() throws SQLException {
        when(resultSet.next()).thenReturn(false);

        List<KeywordHit> hits = service.search("wgh_kb_123", "混合检索", 10, KeywordFilter.none());

        assertThat(hits).isEmpty();
        verify(resultSet, never()).getString("text");
    }

    @Test
    @DisplayName("非法表名/空查询：不访问数据库")
    void illegalInput_neverQueriesDatabase() throws SQLException {
        assertThat(service.search("bad;drop table", "q", 10, KeywordFilter.none())).isEmpty();
        assertThat(service.search("wgh_kb_123", "  ", 10, KeywordFilter.none())).isEmpty();
        verify(dataSource, never()).getConnection();
    }
}
