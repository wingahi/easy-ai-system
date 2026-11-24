package com.wgh.rag.kb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.common.metrics.RagMetrics;
import com.wgh.rag.kb.port.KeywordFilter;
import com.wgh.rag.kb.port.KeywordHit;
import com.wgh.rag.kb.port.KeywordSearchBackend;
import com.wgh.rag.kb.port.vector.VectorScoreType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
 * 关键词检索服务 - 基于 pg_trgm 相似度的关键词召回（混合检索关键词路）。
 * chunk 原文在 pgvector 表 text 列，无需新表；GIN 索引惰性创建（幂等，进程内缓存）。
 * <p>
 * 对外实现 {@link KeywordSearchBackend}，PG 方言细节（pg_trgm / GIN）收敛在本类。
 * pg_trgm 不支持高亮，高亮列表恒为空（高亮由独立搜索引擎后端提供）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KeywordSearchService implements KeywordSearchBackend {

    /** 表名白名单：仅允许字母数字下划线（向量表名为系统生成，杜绝注入） */
    static final Pattern TABLE_NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_]+$");
    static final String TRGM_INDEX_SUFFIX = "_text_trgm_idx";

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

    /** 索引构建耗时指标；字段注入避免改变既有构造器签名（单测直接 new 时不注入）。 */
    @Autowired(required = false)
    private RagMetrics metrics;

    /** 已确保 GIN 索引的表（进程内缓存，CREATE INDEX IF NOT EXISTS 幂等） */
    private final Set<String> indexedTables = ConcurrentHashMap.newKeySet();

    @Override
    public String backendName() {
        return "pgtrgm";
    }

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
    public List<KeywordHit> search(String collection, String query, int topN, KeywordFilter filter) {
        if (collection == null || !TABLE_NAME_PATTERN.matcher(collection).matches()) {
            log.warn("非法向量表名，拒绝关键词检索: {}", collection);
            return List.of();
        }
        if (query == null || query.isBlank() || topN <= 0) return List.of();

        ensureTrgmIndex(collection);

        StringBuilder sql = new StringBuilder("SELECT embedding_id, text, metadata, word_similarity(?, text) AS score ")
                .append("FROM ").append(collection)
                .append(" WHERE word_similarity(?, text) >= ?");
        List<Object> params = new ArrayList<>();
        params.add(query);
        params.add(query);
        params.add(WORD_SIMILARITY_THRESHOLD);
        appendFilter(sql, params, filter);
        sql.append(" ORDER BY score DESC LIMIT ?");
        params.add(topN);

        List<KeywordHit> hits = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    hits.add(new KeywordHit(rs.getString("embedding_id"), rs.getDouble("score"),
                            VectorScoreType.KEYWORD, rs.getString("text"),
                            parseMetadata(rs.getString("metadata")), List.of()));
                }
            }
        } catch (Exception e) {
            // 查询失败必须区别于"无命中"：带异常栈打 WARN，便于定位扩展/表/权限问题
            log.warn("关键词检索失败 table={}, query={}: {}", collection, query, e.toString(), e);
            return List.of();
        }
        if (hits.isEmpty()) {
            log.debug("关键词检索无命中: table={}, query={}, threshold={}", collection, query, WORD_SIMILARITY_THRESHOLD);
        }
        return hits;
    }

    /**
     * 过滤条件翻译为 metadata JSONB 文本谓词（与检索主谓词同一 WHERE，避免先召回后过滤）。
     */
    private void appendFilter(StringBuilder sql, List<Object> params, KeywordFilter filter) {
        if (filter == null) return;
        if (filter.tenantId() != null) {
            sql.append(" AND metadata->>'tenantId' = ?");
            params.add(String.valueOf(filter.tenantId()));
        }
        for (Map.Entry<String, Object> entry : filter.equals().entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) continue;
            if (!TABLE_NAME_PATTERN.matcher(entry.getKey().replace("-", "_")).matches()) continue;
            sql.append(" AND metadata->>'").append(entry.getKey().replace("'", "''")).append("' = ?");
            params.add(String.valueOf(entry.getValue()));
        }
    }

    /**
     * 惰性创建 pg_trgm GIN 索引（幂等；失败不阻断，仅降级关键词路性能）
     */
    private void ensureTrgmIndex(String tableName) {
        if (!indexedTables.add(tableName)) return;
        long start = System.nanoTime();
        try (Connection conn = dataSource.getConnection();
             var stmt = conn.createStatement()) {
            stmt.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");
            stmt.execute("CREATE INDEX IF NOT EXISTS " + tableName + TRGM_INDEX_SUFFIX
                    + " ON " + tableName + " USING GIN (text gin_trgm_ops)");
            log.info("✓ 已为 {} 创建 pg_trgm GIN 索引", tableName);
            if (metrics != null) {
                metrics.recordIndexBuild(backendName(), tableName, System.nanoTime() - start);
            }
        } catch (Exception e) {
            indexedTables.remove(tableName);
            log.warn("创建 pg_trgm 索引失败 table={}: {}", tableName, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMetadata(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
