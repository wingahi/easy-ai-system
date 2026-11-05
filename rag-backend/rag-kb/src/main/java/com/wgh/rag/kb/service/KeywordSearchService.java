package com.wgh.rag.kb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.kb.port.KeywordSearchPort;
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
 * <p>
 * 对外实现 {@link KeywordSearchPort}，PG 方言细节（pg_trgm / GIN）收敛在本类。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KeywordSearchService implements KeywordSearchPort {

    /** 表名白名单：仅允许字母数字下划线（向量表名为系统生成，杜绝注入） */
    private static final Pattern TABLE_NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_]+$");
    private static final String TRGM_INDEX_SUFFIX = "_text_trgm_idx";

    /**
     * word_similarity 命中阈值（0~1）。
     * <p>
     * 选型依据（离线 pg_trgm 同构对照，语料为仓库文档切出的 1077 个 ~512 字块，均值 463 字）：
     * 完全包含的 12 字中文查询相似度 0.86；单字错别字 0.53；双错别字 0.30；库外无关查询 0.05。
     * 0.6 会漏掉单错别字（0.53），0.4 与 0.5 的命中集相同但 0.5 更保守，故取 0.5。
     * 真实库上可用下列 SQL 复核（word_similarity(查询, chunk) 与函数名/参数顺序一致）：
     * SELECT word_similarity('查询串', text) FROM <wgh_kb_表> ORDER BY 1 DESC LIMIT 10;
     */
    private static final double WORD_SIMILARITY_THRESHOLD = 0.5;

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    /** 已确保 GIN 索引的表（进程内缓存，CREATE INDEX IF NOT EXISTS 幂等） */
    private final Set<String> indexedTables = ConcurrentHashMap.newKeySet();

    /**
     * 关键词召回：word_similarity(query, text) 降序取 topN。
     * <p>
     * 不能用 similarity(text, query)：长 chunk（~512 字，约 510 个 trigram）对短 query（~8 个 trigram）
     * 的相似度被分母稀释到 1% 量级，低于默认阈值 0.3，导致关键词路恒为空。
     * word_similarity 以 query 的 trigram 数量为窗口在 text 上滑动取最大匹配，专为"短 query vs 长文本"设计。
     * <p>
     * 查询失败（扩展缺失/表不存在/SQL 异常）记 WARN 并返回空列表；无命中只记 DEBUG，
     * 两种结果在日志上可区分，调用方仍自动退化为纯向量路。
     */
    @Override
    public List<EmbeddingMatch<TextSegment>> search(String tableName, String query, int topN) {
        if (tableName == null || !TABLE_NAME_PATTERN.matcher(tableName).matches()) {
            log.warn("非法向量表名，拒绝关键词检索: {}", tableName);
            return List.of();
        }
        if (query == null || query.isBlank()) return List.of();

        ensureTrgmIndex(tableName);

        String sql = "SELECT embedding_id, text, metadata, word_similarity(?, text) AS score "
                + "FROM " + tableName
                + " WHERE word_similarity(?, text) >= ? ORDER BY score DESC LIMIT ?";
        List<EmbeddingMatch<TextSegment>> matches = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, query);
            ps.setString(2, query);
            ps.setDouble(3, WORD_SIMILARITY_THRESHOLD);
            ps.setInt(4, topN);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    TextSegment segment = TextSegment.from(rs.getString("text"), parseMetadata(rs.getString("metadata")));
                    matches.add(new EmbeddingMatch<>(rs.getDouble("score"),
                            rs.getString("embedding_id"), null, segment));
                }
            }
        } catch (Exception e) {
            // 查询失败必须区别于"无命中"：带异常栈打 WARN，便于定位扩展/表/权限问题
            log.warn("关键词检索失败 table={}, query={}: {}", tableName, query, e.toString(), e);
            return List.of();
        }
        if (matches.isEmpty()) {
            log.debug("关键词检索无命中: table={}, query={}, threshold={}", tableName, query, WORD_SIMILARITY_THRESHOLD);
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
