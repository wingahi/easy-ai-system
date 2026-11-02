package com.wgh.rag.kb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 关键词检索服务 - 基于 pg_trgm 相似度的关键词召回（混合检索关键词路）
 * chunk 原文在 pgvector 表 text 列，无需新表；GIN 索引惰性创建（幂等，进程内缓存）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KeywordSearchService {

    /** 表名白名单：仅允许字母数字下划线（向量表名为系统生成，杜绝注入） */
    private static final Pattern TABLE_NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_]+$");
    private static final String TRGM_INDEX_SUFFIX = "_text_trgm_idx";

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    /** 已确保 GIN 索引的表（进程内缓存，CREATE INDEX IF NOT EXISTS 幂等） */
    private final Set<String> indexedTables = ConcurrentHashMap.newKeySet();

    /**
     * 关键词召回：similarity(text, query) 降序取 topN。
     * 任何失败（扩展缺失/表不存在）记 WARN 并返回空列表 —— 调用方自动退化为纯向量路。
     */
    public List<EmbeddingMatch<TextSegment>> search(String tableName, String query, int topN) {
        if (tableName == null || !TABLE_NAME_PATTERN.matcher(tableName).matches()) {
            log.warn("非法向量表名，拒绝关键词检索: {}", tableName);
            return List.of();
        }
        if (query == null || query.isBlank()) return List.of();

        ensureTrgmIndex(tableName);

        String sql = "SELECT embedding_id, text, metadata, similarity(text, ?) AS score "
                + "FROM " + tableName + " WHERE text % ? ORDER BY score DESC LIMIT ?";
        List<EmbeddingMatch<TextSegment>> matches = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, query);
            ps.setString(2, query);
            ps.setInt(3, topN);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    TextSegment segment = TextSegment.from(rs.getString("text"), parseMetadata(rs.getString("metadata")));
                    matches.add(new EmbeddingMatch<>(rs.getDouble("score"),
                            rs.getString("embedding_id"), null, segment));
                }
            }
        } catch (Exception e) {
            log.warn("关键词检索失败 table={}: {}", tableName, e.getMessage());
            return List.of();
        }
        return matches;
    }

    /**
     * 惰性创建 pg_trgm GIN 索引（幂等；失败不阻断，仅降级关键词路性能）
     */
    private void ensureTrgmIndex(String tableName) {
        if (!indexedTables.add(tableName)) return;
        try (Connection conn = dataSource.getConnection();
             var stmt = conn.createStatement()) {
            stmt.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");
            stmt.execute("CREATE INDEX IF NOT EXISTS " + tableName + TRGM_INDEX_SUFFIX
                    + " ON " + tableName + " USING GIN (text gin_trgm_ops)");
            log.info("✓ 已为 {} 创建 pg_trgm GIN 索引", tableName);
        } catch (Exception e) {
            indexedTables.remove(tableName);
            log.warn("创建 pg_trgm 索引失败 table={}: {}", tableName, e.getMessage());
        }
    }

    private Metadata parseMetadata(String json) {
        if (json == null || json.isBlank()) return new Metadata();
        try {
            return new Metadata(objectMapper.readValue(json, Map.class));
        } catch (Exception e) {
            return new Metadata();
        }
    }
}
