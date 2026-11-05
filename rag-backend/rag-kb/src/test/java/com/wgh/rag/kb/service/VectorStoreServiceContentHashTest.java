package com.wgh.rag.kb.service;

import com.wgh.rag.config.property.PgVectorProperties;
import com.wgh.rag.llm.factory.ModelCacheManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 向量表内容哈希读取：正常返回去重集合；表不存在/查询失败降级为空集合且不抛出。
 */
@DisplayName("向量存储 - contentHash 读取")
class VectorStoreServiceContentHashTest {

    private final DataSource dataSource = Mockito.mock(DataSource.class);
    private final Connection connection = Mockito.mock(Connection.class);
    private final Statement statement = Mockito.mock(Statement.class);
    private final ResultSet tableCheck = Mockito.mock(ResultSet.class);
    private final ResultSet hashRows = Mockito.mock(ResultSet.class);

    private VectorStoreService service;

    @BeforeEach
    void setUp() throws Exception {
        PgVectorProperties props = new PgVectorProperties();
        props.setCreateTable(false);
        props.setSkipCreateVectorExtension(true);
        props.setUseIndex(false);
        props.setUseHnswIndex(false);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        service = new VectorStoreService(dataSource, props, Mockito.mock(ModelCacheManager.class));
    }

    @Test
    @DisplayName("表存在：返回全部非空 contentHash")
    void existingContentHashes_returnsRows() throws Exception {
        when(statement.executeQuery(contains("information_schema.tables"))).thenReturn(tableCheck);
        when(tableCheck.next()).thenReturn(true);

        when(statement.executeQuery(contains("contentHash"))).thenReturn(hashRows);
        when(hashRows.next()).thenReturn(true, true, false);
        when(hashRows.getString(1)).thenReturn("aaa", "bbb");

        assertThat(service.existingContentHashes("kb_a")).containsExactlyInAnyOrder("aaa", "bbb");
    }

    @Test
    @DisplayName("表不存在：返回空集合，不再执行哈希查询")
    void existingContentHashes_missingTable() throws Exception {
        when(statement.executeQuery(contains("information_schema.tables"))).thenReturn(tableCheck);
        when(tableCheck.next()).thenReturn(false);

        assertThat(service.existingContentHashes("kb_a")).isEmpty();
        verify(statement, never()).executeQuery(contains("contentHash"));
    }

    @Test
    @DisplayName("查询失败：记 WARN 并降级为空集合，不中断入库")
    void existingContentHashes_queryFailure_degrades() throws Exception {
        when(statement.executeQuery(contains("information_schema.tables"))).thenReturn(tableCheck);
        when(tableCheck.next()).thenReturn(true);
        when(statement.executeQuery(contains("contentHash")))
                .thenThrow(new SQLException("extension missing"));

        assertThat(service.existingContentHashes("kb_a")).isEmpty();
    }
}
