package com.wgh.rag.data.tenant;

import com.wgh.rag.common.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证租户 GUC 只在事务开始时下发，且无上下文时不下发。
 */
class TenantAwareDataSourceTransactionManagerTest {

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void setsTenantGucWhenContextPresent() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.prepareStatement(anyString())).thenReturn(statement);

        TenantAwareDataSourceTransactionManager manager =
                new TenantAwareDataSourceTransactionManager(dataSource, true);
        TenantContext.set("tenant-a", 7L, "alice");

        TransactionStatus status = manager.getTransaction(new DefaultTransactionDefinition());
        verify(statement).setString(1, "tenant-a");
        verify(statement).execute();
        manager.commit(status);
    }

    @Test
    void skipsGucWhenNoTenantContext() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);

        TenantAwareDataSourceTransactionManager manager =
                new TenantAwareDataSourceTransactionManager(dataSource, true);

        TransactionStatus status = manager.getTransaction(new DefaultTransactionDefinition());
        verify(connection, never()).prepareStatement(anyString());
        manager.commit(status);
    }
}
