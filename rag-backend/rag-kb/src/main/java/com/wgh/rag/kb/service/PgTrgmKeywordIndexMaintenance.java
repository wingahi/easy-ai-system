package com.wgh.rag.kb.service;

import com.wgh.rag.kb.port.KeywordIndexMaintenance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * pg_trgm 关键词索引维护（V1 双路抽象中的对照实现）。
 *
 * <p>pg_trgm 的 GIN 索引建立在向量表 text 列上，与向量数据同源：
 * 删除文件时行由向量路删除，关键词索引无需额外动作；删除集合时先删索引再删表（幂等）；
 * 重建后预建索引，避免首次检索触发 DDL。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PgTrgmKeywordIndexMaintenance implements KeywordIndexMaintenance {

    private final DataSource dataSource;

    @Override
    public String backendName() {
        return "pgtrgm";
    }

    @Override
    public void deleteByFileId(String collection, Long fileId) {
        // 关键词 GIN 索引与向量表同源，fileId 行删除由向量路完成，这里无需动作
        log.debug("pgtrgm 索引随向量表行删除，跳过 fileId={} 的独立清理: {}", fileId, collection);
    }

    @Override
    public void dropCollection(String collection) {
        if (!validTable(collection)) return;
        try (Connection conn = dataSource.getConnection();
             var stmt = conn.createStatement()) {
            stmt.execute("DROP INDEX IF EXISTS " + collection + KeywordSearchService.TRGM_INDEX_SUFFIX);
            log.info("✓ 已删除 {} 的 pg_trgm 索引", collection);
        } catch (Exception e) {
            log.warn("删除 pg_trgm 索引失败 table={}: {}", collection, e.getMessage());
        }
    }

    @Override
    public void ensureIndex(String collection) {
        if (!validTable(collection)) return;
        try (Connection conn = dataSource.getConnection();
             var stmt = conn.createStatement()) {
            stmt.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");
            stmt.execute("CREATE INDEX IF NOT EXISTS " + collection + KeywordSearchService.TRGM_INDEX_SUFFIX
                    + " ON " + collection + " USING GIN (text gin_trgm_ops)");
        } catch (Exception e) {
            log.warn("预建 pg_trgm 索引失败 table={}: {}", collection, e.getMessage());
        }
    }

    private static boolean validTable(String table) {
        return table != null && KeywordSearchService.TABLE_NAME_PATTERN.matcher(table).matches();
    }
}
