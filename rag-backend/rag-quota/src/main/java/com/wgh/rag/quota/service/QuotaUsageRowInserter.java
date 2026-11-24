package com.wgh.rag.quota.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Savepoint;

/**
 * 配额计数行初始化器：在事务内安全地插入「当期用量」首行。
 *
 * <p>背景：PostgreSQL 中一条语句失败后整个事务进入 aborted（25P02），
 * 「catch DuplicateKeyException 后继续 UPDATE」在事务内不可用。
 * 这里用 SAVEPOINT 包住首插：唯一键冲突时只回滚到保存点，外层事务保持可用，
 * PostgreSQL / H2 行为一致。</p>
 *
 * <p>仅在存在外层事务时由 {@link QuotaService} 调用；保存点回滚后该行由并发事务持有，
 * 随后的条件 UPDATE 必然能看到该行。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QuotaUsageRowInserter {

    private static final String INSERT_SQL =
            "INSERT INTO wgh_quota_usage (username, date_key, chat_count, token_used) VALUES (?, ?, 0, 0)";

    private final DataSource dataSource;

    /**
     * 确保当期用量行存在：不存在则插入（计数为 0）；并发冲突时静默忽略。
     */
    public void insertIfAbsent(String username, String dateKey) {
        Connection connection = DataSourceUtils.getConnection(dataSource);
        Savepoint savepoint = null;
        try {
            savepoint = connection.setSavepoint();
            try (PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
                statement.setString(1, username);
                statement.setString(2, dateKey);
                statement.executeUpdate();
            }
            connection.releaseSavepoint(savepoint);
        } catch (SQLException e) {
            rollbackToSavepoint(connection, savepoint);
            if (isUniqueViolation(e)) {
                log.debug("配额计数行已由并发事务创建: username={}, dateKey={}", username, dateKey);
                return;
            }
            throw new DataAccessResourceFailureException("初始化配额计数行失败", e);
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    private static void rollbackToSavepoint(Connection connection, Savepoint savepoint) {
        if (savepoint == null) {
            return;
        }
        try {
            connection.rollback(savepoint);
        } catch (SQLException rollbackError) {
            log.warn("回滚配额计数行保存点失败: {}", rollbackError.getMessage());
        }
    }

    /** 唯一键冲突：PostgreSQL 与 H2 均为 23505。 */
    private static boolean isUniqueViolation(SQLException e) {
        return "23505".equals(e.getSQLState());
    }
}
