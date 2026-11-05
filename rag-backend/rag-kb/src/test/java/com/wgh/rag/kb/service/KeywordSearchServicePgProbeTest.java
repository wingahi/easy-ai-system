package com.wgh.rag.kb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 PostgreSQL 探针（默认跳过）：验证 word_similarity 在 512 字长 chunk 上能命中短 query。
 * 显式开启：WGH_PG_IT=1 mvn -pl rag-kb -am -Dtest=KeywordSearchServicePgProbeTest test
 * 连接参数可覆盖：WGH_IT_DB_URL / WGH_IT_DB_USER / WGH_IT_DB_PASSWORD（默认本地 dev 库）。
 */
@EnabledIfEnvironmentVariable(named = "WGH_PG_IT", matches = "1")
class KeywordSearchServicePgProbeTest {

    @Test
    @DisplayName("PostgreSQL word_similarity：512 字 chunk + 10 字 query 命中")
    void longChunkShortQuery_hitsInRealPostgres() throws Exception {
        String url = env("WGH_IT_DB_URL", "jdbc:postgresql://localhost:5432/wgh_rag");
        DriverManagerDataSource ds = new DriverManagerDataSource(url,
                env("WGH_IT_DB_USER", "postgres"), env("WGH_IT_DB_PASSWORD", "123456"));
        KeywordSearchService service = new KeywordSearchService(ds, new ObjectMapper());
        String table = "wgh_trgm_probe_" + System.currentTimeMillis();

        try (Connection conn = ds.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE " + table + " (embedding_id uuid, text text, metadata jsonb)");
            String chunk = "知识库检索说明。" + "本段用于验证关键词召回语义，".repeat(40) + "其中包含混合检索与向量召回这两个术语，"
                    + "其余内容为无关填充。".repeat(20);
            conn.createStatement().execute("INSERT INTO " + table
                    + " VALUES ('00000000-0000-0000-0000-000000000001', '" + chunk + "', '{}'::jsonb)");

            List<EmbeddingMatch<TextSegment>> matches = service.search(table, "混合检索与向量召回", 10);

            assertThat(matches).isNotEmpty();
            assertThat(matches.get(0).score()).isGreaterThan(0.0);
        } finally {
            try (Connection conn = ds.getConnection(); Statement stmt = conn.createStatement()) {
                stmt.execute("DROP TABLE IF EXISTS " + table);
            }
        }
    }

    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
