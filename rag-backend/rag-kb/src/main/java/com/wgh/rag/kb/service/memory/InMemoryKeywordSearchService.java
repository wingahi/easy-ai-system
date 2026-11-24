package com.wgh.rag.kb.service.memory;

import com.wgh.rag.kb.port.KeywordFilter;
import com.wgh.rag.kb.port.KeywordHit;
import com.wgh.rag.kb.port.KeywordSearchBackend;
import com.wgh.rag.kb.port.vector.VectorScoreType;
import dev.langchain4j.data.segment.TextSegment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存关键词后端（开发/测试与端口切片测试用，不用于生产）。
 *
 * <p>评分规则：原文包含查询得 1.0，否则按字符二元组 Jaccard 相似度打分。
 * 仅用于验证可切换性与过滤语义，不承诺检索质量。</p>
 */
@Slf4j
@Service("memoryKeywordSearch")
@RequiredArgsConstructor
public class InMemoryKeywordSearchService implements KeywordSearchBackend {

    /** collection -> (id -> segment) */
    private final Map<String, Map<String, TextSegment>> indexes = new ConcurrentHashMap<>();

    @Override
    public String backendName() {
        return "memory";
    }

    /** 供同包/测试预置索引；生产链路的内存后端不接收写入（写路径由向量后端负责）。 */
    public void index(String collection, String id, TextSegment segment) {
        indexes.computeIfAbsent(collection, k -> new ConcurrentHashMap<>()).put(id, segment);
    }

    @Override
    public List<KeywordHit> search(String collection, String query, int topN, KeywordFilter filter) {
        Map<String, TextSegment> index = indexes.get(collection);
        if (index == null || query == null || query.isBlank() || topN <= 0) return List.of();
        String normalizedQuery = query.toLowerCase();
        List<KeywordHit> hits = new ArrayList<>();
        for (Map.Entry<String, TextSegment> entry : index.entrySet()) {
            TextSegment segment = entry.getValue();
            if (segment == null || segment.text() == null) continue;
            if (!matches(segment.metadata().toMap(), filter)) continue;
            double score = score(normalizedQuery, segment.text().toLowerCase());
            if (score <= 0) continue;
            hits.add(new KeywordHit(entry.getKey(), score, VectorScoreType.KEYWORD,
                    segment.text(), segment.metadata().toMap(), List.of()));
        }
        hits.sort(Comparator.comparingDouble(KeywordHit::score).reversed());
        return hits.size() <= topN ? hits : hits.subList(0, topN);
    }

    private static double score(String query, String text) {
        if (text.contains(query)) return 1.0;
        Set<String> a = bigrams(query);
        Set<String> b = bigrams(text);
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }

    private static Set<String> bigrams(String value) {
        Set<String> grams = new HashSet<>();
        for (int i = 0; i + 1 < value.length(); i++) {
            grams.add(value.substring(i, i + 2));
        }
        return grams;
    }

    private static boolean matches(Map<String, Object> metadata, KeywordFilter filter) {
        if (filter == null) return true;
        if (filter.tenantId() != null
                && !String.valueOf(filter.tenantId()).equals(String.valueOf(metadata.get("tenantId")))) {
            return false;
        }
        for (Map.Entry<String, Object> entry : filter.equals().entrySet()) {
            if (entry.getValue() == null) continue;
            if (!String.valueOf(entry.getValue()).equals(String.valueOf(metadata.get(entry.getKey())))) {
                return false;
            }
        }
        return true;
    }
}
