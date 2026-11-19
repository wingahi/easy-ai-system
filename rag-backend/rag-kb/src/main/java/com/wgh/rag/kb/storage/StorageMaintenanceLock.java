package com.wgh.rag.kb.storage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 存储维护任务的单飞锁。
 * <p>
 * 使用 PostgreSQL 会话级 advisory lock，锁绑定在同一个数据库连接上，任务结束后在 finally 中释放，
 * 因此多实例部署时只有一个实例能执行定期任务。数据库不支持 advisory lock（例如 H2 测试库）时
 * 降级为“单实例直接执行”，只输出 WARN，不影响功能。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StorageMaintenanceLock {

    private static final String SQL_TRY_LOCK = "SELECT pg_try_advisory_lock(?)";
    private static final String SQL_UNLOCK = "SELECT pg_advisory_unlock(?)";

    private final DataSource dataSource;

    /**
     * 尝试获取指定任务的锁。
     *
     * @return 已获取（或降级放行）时返回句柄；其他实例正在执行时返回 {@code null}
     */
    public Handle tryAcquire(String name) {
        long key = advisoryKey(name);
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            try (PreparedStatement statement = connection.prepareStatement(SQL_TRY_LOCK)) {
                statement.setLong(1, key);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next() && resultSet.getBoolean(1)) {
                        log.debug("已获取存储维护任务锁: {}", name);
                        return new Handle(connection, key, name);
                    }
                }
            }
            closeQuietly(connection);
            return null;
        } catch (SQLException | RuntimeException e) {
            closeQuietly(connection);
            log.warn("当前数据库不支持 advisory lock（{}），按单实例降级执行: {}", e.getMessage(), name);
            return new Handle(null, key, name);
        }
    }

    static long advisoryKey(String name) {
        long hash = 1125899906842597L;
        if (name != null) {
            for (int i = 0; i < name.length(); i++) {
                hash = 31 * hash + name.charAt(i);
            }
        }
        return hash;
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException e) {
            log.debug("关闭存储维护锁连接失败: {}", e.getMessage());
        }
    }

    /** 锁句柄：{@code connection == null} 表示降级模式，close 为空操作。 */
    public final class Handle implements AutoCloseable {

        private final Connection connection;
        private final long key;
        private final String name;

        private Handle(Connection connection, long key, String name) {
            this.connection = connection;
            this.key = key;
            this.name = name;
        }

        @Override
        public void close() {
            if (connection == null) {
                return;
            }
            try (PreparedStatement statement = connection.prepareStatement(SQL_UNLOCK)) {
                statement.setLong(1, key);
                statement.execute();
                log.debug("已释放存储维护任务锁: {}", name);
            } catch (SQLException e) {
                log.warn("释放存储维护任务锁失败: {} ({})", name, e.getMessage());
            } finally {
                closeQuietly(connection);
            }
        }
    }
}
