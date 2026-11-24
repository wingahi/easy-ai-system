package com.wgh.rag.data.tenant;

import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 专用数据库角色切换模板。
 *
 * <p>在独立事务内执行 {@code SET LOCAL ROLE <role>}，事务结束自动还原；
 * 禁止会话级 {@code SET ROLE}，避免连接被池化后把高权限角色带给下一个借用者。
 *
 * <p>角色白名单固定，杜绝把外部输入拼进 SQL。连接仍以应用角色登录，
 * 仅对已授予成员关系的专用角色做临时切换。
 */
@Component
public class DatabaseRoleTemplate {

    /** 允许临时切换的专用角色。 */
    private static final Set<String> ALLOWED_ROLES = Set.of("wgh_preauth", "wgh_scheduler", "wgh_platform");

    private final DataSource dataSource;
    private final PlatformTransactionManager transactionManager;
    private final boolean enabled;

    public DatabaseRoleTemplate(DataSource dataSource,
                                PlatformTransactionManager transactionManager,
                                TenantSqlProperties properties) {
        this.dataSource = dataSource;
        this.transactionManager = transactionManager;
        this.enabled = properties.isRoleSwitchEnabled();
    }

    /** 只读事务内以指定角色执行。 */
    public <T> T runReadOnlyAs(String role, Supplier<T> action) {
        return run(role, true, action);
    }

    /** 读写事务内以指定角色执行。 */
    public <T> T runAs(String role, Supplier<T> action) {
        return run(role, false, action);
    }

    private <T> T run(String role, boolean readOnly, Supplier<T> action) {
        if (role == null || !ALLOWED_ROLES.contains(role)) {
            throw new IllegalArgumentException("未授权的数据库角色切换: " + role);
        }
        if (!enabled) {
            TransactionTemplate fallback = new TemplateBuilder(transactionManager).build(readOnly);
            return fallback.execute(status -> action.get());
        }
        TransactionTemplate template = new TemplateBuilder(transactionManager).build(readOnly);
        return template.execute(status -> {
            switchRole(role);
            return action.get();
        });
    }

    private void switchRole(String role) {
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET LOCAL ROLE " + role);
        } catch (SQLException e) {
            throw new IllegalStateException("切换数据库角色失败: " + role, e);
        }
    }

    /** 每次独立事务：角色随事务结束自动还原。 */
    private static final class TemplateBuilder {

        private final PlatformTransactionManager transactionManager;

        private TemplateBuilder(PlatformTransactionManager transactionManager) {
            this.transactionManager = transactionManager;
        }

        private TransactionTemplate build(boolean readOnly) {
            TransactionTemplate template = new TransactionTemplate(transactionManager);
            template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            template.setReadOnly(readOnly);
            return template;
        }
    }
}
