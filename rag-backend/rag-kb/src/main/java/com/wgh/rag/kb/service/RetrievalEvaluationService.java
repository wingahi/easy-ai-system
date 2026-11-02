package com.wgh.rag.kb.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.result.ResultCode;
import com.wgh.rag.kb.entity.KbGroundTruth;
import com.wgh.rag.kb.entity.KnowledgeBase;
import com.wgh.rag.kb.mapper.KbGroundTruthMapper;
import com.wgh.rag.kb.mapper.KnowledgeBaseMapper;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 检索评测服务：维护 ground truth 标注，并据此计算真实 Recall@K。
 *
 * <p>Recall@K = |检索结果 ∩ 标注相关集合| / |标注相关集合|，分母是标注的相关 chunk 数而非 topK。
 * chunk 以 "fileId:chunkIndex" 为键，与 pgvector 表 metadata 对应，重新向量化后仍可用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalEvaluationService {

    /** 向量表名白名单：仅允许字母数字下划线（表名由系统生成，杜绝注入） */
    private static final Pattern TABLE_NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_]+$");
    private static final int MAX_PAGE_SIZE = 500;

    private final KbGroundTruthMapper groundTruthMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final RagRetrieveService retrieveService;
    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    /** 标注请求：查询 + 相关 chunk 键（"fileId:chunkIndex"） */
    public record GroundTruthPayload(String queryText, List<String> relevantChunks) {
    }

    public List<KbGroundTruth> list(Long kbId) {
        return groundTruthMapper.selectList(new LambdaQueryWrapper<KbGroundTruth>()
                .eq(KbGroundTruth::getKnowledgeBaseId, kbId)
                .orderByAsc(KbGroundTruth::getId));
    }

    public KbGroundTruth save(Long kbId, GroundTruthPayload payload) {
        KbGroundTruth gt = new KbGroundTruth();
        gt.setKnowledgeBaseId(kbId);
        applyPayload(gt, payload);
        groundTruthMapper.insert(gt);
        return gt;
    }

    /**
     * 批量导入：同一知识库内相同查询已存在则覆盖标注，避免重复导入产生重复评测样本
     */
    public List<KbGroundTruth> importBatch(Long kbId, List<GroundTruthPayload> payloads) {
        if (payloads == null || payloads.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "导入数据为空");
        }
        Map<String, KbGroundTruth> existing = new HashMap<>();
        for (KbGroundTruth gt : list(kbId)) {
            existing.put(gt.getQueryText(), gt);
        }
        List<KbGroundTruth> saved = new ArrayList<>(payloads.size());
        for (GroundTruthPayload payload : payloads) {
            KbGroundTruth gt = existing.get(payload.queryText());
            if (gt != null) {
                applyPayload(gt, payload);
                groundTruthMapper.updateById(gt);
            } else {
                gt = save(kbId, payload);
                existing.put(gt.getQueryText(), gt);
            }
            saved.add(gt);
        }
        return saved;
    }

    public KbGroundTruth update(Long kbId, Long id, GroundTruthPayload payload) {
        KbGroundTruth gt = requireOwned(kbId, id);
        applyPayload(gt, payload);
        groundTruthMapper.updateById(gt);
        return gt;
    }

    public void delete(Long kbId, Long id) {
        requireOwned(kbId, id);
        groundTruthMapper.deleteById(id);
    }

    /**
     * 逐条评测标注查询，返回聚合 Recall@K / Precision@K / 查询命中率与明细。
     * K 由 options.topK() 指定（null 用全局配置）。
     */
    public Map<String, Object> evaluate(Long kbId, RetrievalOptions options) {
        List<KbGroundTruth> truths = list(kbId);
        if (truths.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "该知识库暂无标注数据，无法计算 Recall@K");
        }

        List<Map<String, Object>> details = new ArrayList<>();
        double recallSum = 0.0;
        double precisionSum = 0.0;
        double coverageSum = 0.0;
        int recallCount = 0;
        int hitQueries = 0;
        int effectiveTopK = 0;
        long latencySum = 0;

        for (KbGroundTruth gt : truths) {
            Set<String> relevant = parseKeys(gt.getRelevantChunks());
            if (relevant.isEmpty()) {
                continue;
            }
            long start = System.currentTimeMillis();
            RetrieveResult result = retrieveService.searchMultiple(
                    List.of(kbId), gt.getQueryText(), options);
            long latency = System.currentTimeMillis() - start;

            Set<String> retrieved = new LinkedHashSet<>();
            for (EmbeddingMatch<TextSegment> match : result.matches()) {
                String key = chunkKey(match);
                if (key != null) retrieved.add(key);
            }
            long hits = relevant.stream().filter(retrieved::contains).count();
            Double recall = RetrievalQuality.recallAtK(relevant, retrieved);
            double precision = RetrievalQuality.hitRate((int) hits, retrieved.size());
            int topK = options != null && options.topK() != null && options.topK() > 0
                    ? options.topK() : retrieved.size();
            effectiveTopK = Math.max(effectiveTopK, topK);

            if (recall != null) {
                recallSum += recall;
                recallCount++;
            }
            precisionSum += precision;
            coverageSum += RetrievalQuality.coverage(retrieved.size(), topK);
            latencySum += latency;
            boolean hit = hits > 0;
            if (hit) hitQueries++;

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("query", gt.getQueryText());
            item.put("relevantCount", relevant.size());
            item.put("retrievedCount", retrieved.size());
            item.put("hitCount", hits);
            item.put("recall", recall != null ? round4(recall) : null);
            item.put("precision", round4(precision));
            item.put("hit", hit);
            item.put("latencyMs", latency);
            item.put("retrievedKeys", retrieved);
            details.add(item);
        }

        if (details.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "标注数据缺少有效相关 chunk，无法计算 Recall@K");
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("kbId", kbId);
        resp.put("topK", effectiveTopK);
        resp.put("queryCount", details.size());
        resp.put("recallAtK", round4(recallSum / recallCount));
        resp.put("precisionAtK", round4(precisionSum / details.size()));
        resp.put("queryHitRate", round4(hitQueries / (double) details.size()));
        resp.put("coverageRate", round4(coverageSum / details.size()));
        resp.put("avgLatencyMs", latencySum / details.size());
        resp.put("details", details);
        return resp;
    }

    /**
     * 列出知识库 chunk（来自 pgvector 表），用于标注相关 chunk 与离线评测扫描语料
     */
    public Map<String, Object> listChunks(Long kbId, Long fileId, int page, int size) {
        KnowledgeBase kb = knowledgeBaseMapper.selectById(kbId);
        if (kb == null) {
            throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "知识库不存在");
        }
        String tableName = kb.getCollectionName();
        if (tableName == null || !TABLE_NAME_PATTERN.matcher(tableName).matches()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "知识库向量表名非法");
        }
        int p = Math.max(1, page);
        int s = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
        boolean byFile = fileId != null;
        String where = byFile ? " WHERE metadata->>'fileId' = ?" : "";

        try (Connection conn = dataSource.getConnection()) {
            long total;
            try (PreparedStatement ps = conn.prepareStatement("SELECT count(*) FROM " + tableName + where)) {
                if (byFile) ps.setString(1, String.valueOf(fileId));
                try (ResultSet rs = ps.executeQuery()) {
                    total = rs.next() ? rs.getLong(1) : 0L;
                }
            }

            String sql = "SELECT embedding_id, text, metadata FROM " + tableName + where
                    + " ORDER BY (metadata->>'chunkIndex')::int LIMIT ? OFFSET ?";
            List<Map<String, Object>> chunks = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                int idx = 1;
                if (byFile) ps.setString(idx++, String.valueOf(fileId));
                ps.setInt(idx++, s);
                ps.setInt(idx, (p - 1) * s);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("embeddingId", rs.getString("embedding_id"));
                        item.put("text", rs.getString("text"));
                        Map<String, Object> meta = readMetadata(rs.getString("metadata"));
                        item.put("fileId", toLong(meta.get("fileId")));
                        item.put("fileName", meta.get("fileName"));
                        item.put("chunkIndex", toInt(meta.get("chunkIndex")));
                        chunks.add(item);
                    }
                }
            }

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("total", total);
            resp.put("page", p);
            resp.put("size", s);
            resp.put("chunks", chunks);
            return resp;
        } catch (Exception e) {
            log.warn("列出 chunk 失败 kbId={} table={}: {}", kbId, tableName, e.getMessage());
            throw new BusinessException(ResultCode.FAIL, "读取知识库 chunk 失败：" + e.getMessage());
        }
    }

    // ============ 私有方法 ============

    /** chunk 键：fileId:chunkIndex，与标注格式一致 */
    static String chunkKey(EmbeddingMatch<TextSegment> match) {
        if (match.embedded() == null || match.embedded().metadata() == null) return null;
        try {
            String fileId = match.embedded().metadata().getString("fileId");
            String chunkIndex = match.embedded().metadata().getString("chunkIndex");
            if (fileId == null || chunkIndex == null) return null;
            return fileId + ":" + chunkIndex;
        } catch (Exception e) {
            return null;
        }
    }

    private void applyPayload(KbGroundTruth gt, GroundTruthPayload payload) {
        if (payload == null || payload.queryText() == null || payload.queryText().isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "查询不能为空");
        }
        if (payload.relevantChunks() == null || payload.relevantChunks().isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "相关 chunk 集合不能为空");
        }
        Set<String> keys = new LinkedHashSet<>();
        for (String key : payload.relevantChunks()) {
            if (key != null && !key.isBlank()) keys.add(key.trim());
        }
        if (keys.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "相关 chunk 集合不能为空");
        }
        gt.setQueryText(payload.queryText().trim());
        try {
            gt.setRelevantChunks(objectMapper.writeValueAsString(keys));
        } catch (Exception e) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "相关 chunk 集合序列化失败");
        }
    }

    private KbGroundTruth requireOwned(Long kbId, Long id) {
        KbGroundTruth gt = groundTruthMapper.selectById(id);
        if (gt == null || !Objects.equals(kbId, gt.getKnowledgeBaseId())) {
            throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "标注不存在");
        }
        return gt;
    }

    private Set<String> parseKeys(String json) {
        if (json == null || json.isBlank()) return Set.of();
        try {
            return new LinkedHashSet<>(objectMapper.readValue(json, new TypeReference<List<String>>() {
            }));
        } catch (Exception e) {
            log.warn("解析标注相关 chunk 失败: {}", json);
            return Set.of();
        }
    }

    private Map<String, Object> readMetadata(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static Long toLong(Object v) {
        if (v == null) return null;
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer toInt(Object v) {
        if (v == null) return null;
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
