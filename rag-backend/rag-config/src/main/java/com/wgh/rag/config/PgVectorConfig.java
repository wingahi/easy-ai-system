package com.wgh.rag.config;

import com.wgh.rag.config.property.PgVectorProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * PostgreSQL PGVector 向量存储配置
 * <p>
 * DataSource 由 Spring Boot 通过 application.yml 统一管理（HikariCP），
 * 本类只负责 pgvector 扩展健康检查 + VectorStoreService 所需依赖注入
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class PgVectorConfig {

    private final PgVectorProperties p;

    /**
     * 启动时验证 PostgreSQL + pgvector 扩展可用
     */
    @Bean
    @ConditionalOnProperty(name = "wgh.pgvector.connect-on-startup", havingValue = "true", matchIfMissing = true)
    public CommandLineRunner pgVectorHealthCheckRunner(DataSource dataSource) {
        return args -> {
            if (Boolean.TRUE.equals(p.getSkipCreateVectorExtension())) {
                log.info("▶ 检查 PostgreSQL + pgvector 扩展 (skipCreateVectorExtension=true)");
            } else {
                log.info("▶ 检查 PostgreSQL + pgvector 扩展");
            }
            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement()) {

                // 1. 尝试 CREATE EXTENSION（如果 skipCreateVectorExtension=true 会被 PGVectorEmbeddingStore 忽略）
                if (!Boolean.TRUE.equals(p.getSkipCreateVectorExtension())) {
                    stmt.execute("CREATE EXTENSION IF NOT EXISTS vector");
                }

                // 2. 确认扩展已启用
                ResultSet rs = stmt.executeQuery(
                        "SELECT extname FROM pg_extension WHERE extname = 'vector'");
                if (rs.next()) {
                    log.info("✓ PostgreSQL pgvector 扩展已启用");
                } else {
                    throw new RuntimeException(
                            "pgvector 扩展未安装，请在 DB 侧执行: CREATE EXTENSION vector;");
                }

            } catch (Exception e) {
                if (Boolean.TRUE.equals(p.getAllowFallbackToInMemory())) {
                    log.warn("⚠ PostgreSQL 或 pgvector 不可用，但 allowFallbackToInMemory=true，跳过启动阻断: {}", e.getMessage());
                } else {
                    log.error("✗ PostgreSQL / pgvector 连接失败");
                    throw new RuntimeException("PostgreSQL pgvector 初始化失败", e);
                }
            }
        };
    }
}
