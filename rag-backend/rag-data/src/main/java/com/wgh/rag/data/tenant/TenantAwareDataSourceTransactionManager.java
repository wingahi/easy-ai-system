package com.wgh.rag.data.tenant;

import com.wgh.rag.common.security.TenantContext;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * 事务级租户上下文下发。
 *
 * <p>在既有事务开始时（{@link #doBegin}）对绑定连接执行
 * {@code set_config('app.tenant_id', ?, true)}；{@code is_local=true} 保证事务结束自动失效，
 * 不使用会话级 {@code SET}，也不修改 Hikari 的全局 {@code auto-commit}。
 *
 * <p>无租户上下文（启动自检、维护任务、平台内部调用）不下发任何值，
 * 由调用方显式进入 {@link TenantContext} 或走专用角色（见 {@link DatabaseRoleTemplate}）。
 */
public class TenantAwareDataSourceTransactionManager extends DataSourceTransactionManager {

    private static final String SET_TENANT_SQL = "SELECT set_config('app.tenant_id', ?, true)";

    private final boolean gucEnabled;

    public TenantAwareDataSourceTransactionManager(DataSource dataSource, boolean gucEnabled) {
        super(dataSource);
        this.gucEnabled = gucEnabled;
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        super.doBegin(transaction, definition);
        if (!gucEnabled) {
            return;
        }
        String tenantId = TenantContext.currentTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            return;
        }
        ConnectionHolder holder =
                (ConnectionHolder) TransactionSynchronizationManager.getResource(obtainDataSource());
        if (holder == null) {
            return;
        }
        Connection connection = holder.getConnection();
        try (PreparedStatement statement = connection.prepareStatement(SET_TENANT_SQL)) {
            statement.setString(1, tenantId);
            statement.execute();
        } catch (SQLException e) {
            throw new CannotCreateTransactionException(
                    "事务开始时下发 app.tenant_id 失败: tenant=" + tenantId, e);
        }
    }
}
