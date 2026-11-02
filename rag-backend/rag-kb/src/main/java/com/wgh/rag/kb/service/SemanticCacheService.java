package com.wgh.rag.kb.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wgh.rag.config.property.RagProperties;
import com.wgh.rag.kb.entity.KnowledgeBase;
import com.wgh.rag.kb.entity.SemanticCache;
import com.wgh.rag.kb.mapper.KnowledgeBaseMapper;
import com.wgh.rag.kb.mapper.SemanticCacheMapper;
import com.wgh.rag.llm.factory.ModelCacheManager;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 语义缓存服务 - 相似问题直接返回缓存答案，跳过检索 + LLM。
 * 向量存 pgvector 独立表 wgh_semantic_cache_vec（metadata.windowId 做窗口隔离），
 * 问答对元数据存 wgh_semantic_cache；维度取窗口第一个可用知识库的 embedding 模型。
 * 任何异常只记日志并降级为正常流程，不影响聊天主链路。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SemanticCacheService {

    /** pgvector 向量表名（由 PgVectorEmbeddingStore 自动建表） */
    public static final String VEC_TABLE = "wgh_semantic_cache_vec";

    private final SemanticCacheMapper cacheMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final VectorStoreService vectorStoreService;
    private final ModelCacheManager modelCacheManager;
    private final RagProperties ragProperties;

    /** 缓存命中结果 */
    public record CacheHit(String answer, double score, Long cacheId) {}

    /**
     * 查询语义缓存：相似度 >= 阈值时返回缓存答案并累加命中计数，否则返回 null。
     */
    public CacheHit lookup(Long windowId, List<Long> kbIds, String query) {
        if (!enabled() || windowId == null || kbIds == null || kbIds.isEmpty()
                || query == null || query.isBlank()) {
            return null;
        }
        try {
            EmbeddingModel embeddingModel = resolveEmbeddingModel(kbIds);
            if (embeddingModel == null) return null;
            int dimension = resolveDimension(kbIds);

            Embedding queryEmbedding = embeddingModel.embed(query).content();
            EmbeddingStore<TextSegment> store = vectorStoreService.createEmbeddingStore(VEC_TABLE, dimension);
            EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(1)
                    .filter(new IsEqualTo("windowId", String.valueOf(windowId)))
                    .build();
            List<EmbeddingMatch<TextSegment>> matches = store.search(request).matches();
            if (matches.isEmpty()) return null;

            EmbeddingMatch<TextSegment> top = matches.get(0);
            double threshold = ragProperties.getCache().getSimilarityThreshold() != null
                    ? ragProperties.getCache().getSimilarityThreshold() : 0.95;
            if (top.score() == null || top.score() < threshold) return null;

            SemanticCache row = cacheMapper.selectOne(new LambdaQueryWrapper<SemanticCache>()
                    .eq(SemanticCache::getWindowId, windowId)
                    .eq(SemanticCache::getEmbeddingId, top.embeddingId())
                    .last("LIMIT 1"));
            if (row == null || row.getAnswerText() == null || row.getAnswerText().isBlank()) return null;

            // 命中计数（尽力而为）
            try {
                SemanticCache inc = new SemanticCache();
                inc.setId(row.getId());
                inc.setHitCount((row.getHitCount() != null ? row.getHitCount() : 0) + 1);
                cacheMapper.updateById(inc);
            } catch (Exception e) {
                log.warn("语义缓存命中计数失败: id={}, reason={}", row.getId(), e.getMessage());
            }
            log.info("✓ 语义缓存命中: windowId={}, cacheId={}, score={}", windowId, row.getId(),
                    String.format("%.4f", top.score()));
            return new CacheHit(row.getAnswerText(), top.score(), row.getId());
        } catch (Exception e) {
            log.warn("语义缓存查询失败（降级正常流程）: windowId={}, reason={}", windowId, e.getMessage());
            return null;
        }
    }

    /**
     * 写入语义缓存（问答对 + 向量），随后按 max-per-window 淘汰最旧条目。
     */
    public void save(Long windowId, List<Long> kbIds, String query, String answer) {
        if (!enabled() || windowId == null || kbIds == null || kbIds.isEmpty()
                || query == null || query.isBlank() || answer == null || answer.isBlank()) {
            return;
        }
        try {
            EmbeddingModel embeddingModel = resolveEmbeddingModel(kbIds);
            if (embeddingModel == null) return;
            int dimension = resolveDimension(kbIds);

            Embedding embedding = embeddingModel.embed(query).content();
            TextSegment segment = TextSegment.from(query,
                    Metadata.from(Map.of("windowId", String.valueOf(windowId))));
            EmbeddingStore<TextSegment> store = vectorStoreService.createEmbeddingStore(VEC_TABLE, dimension);
            String embeddingId = store.add(embedding, segment);

            SemanticCache row = new SemanticCache();
            row.setWindowId(windowId);
            row.setQueryText(query.length() > 2000 ? query.substring(0, 2000) : query);
            row.setAnswerText(answer);
            row.setEmbeddingId(embeddingId);
            row.setHitCount(0);
            cacheMapper.insert(row);
            log.info("✓ 语义缓存写入: windowId={}, cacheId={}", windowId, row.getId());

            evictIfNeeded(windowId);
        } catch (Exception e) {
            log.warn("语义缓存写入失败: windowId={}, reason={}", windowId, e.getMessage());
        }
    }

    /** 超出 max-per-window 时物理删除最旧条目及其向量 */
    private void evictIfNeeded(Long windowId) {
        int max = ragProperties.getCache().getMaxPerWindow() != null
                ? ragProperties.getCache().getMaxPerWindow() : 500;
        if (max <= 0) return;
        Long count = cacheMapper.selectCount(new LambdaQueryWrapper<SemanticCache>()
                .eq(SemanticCache::getWindowId, windowId));
        if (count == null || count <= max) return;

        long evictNum = count - max;
        List<SemanticCache> oldest = cacheMapper.selectList(new LambdaQueryWrapper<SemanticCache>()
                .eq(SemanticCache::getWindowId, windowId)
                .orderByAsc(SemanticCache::getCreateTime)
                .last("LIMIT " + evictNum));
        List<String> vecIds = oldest.stream()
                .map(SemanticCache::getEmbeddingId)
                .filter(Objects::nonNull)
                .toList();
        for (SemanticCache row : oldest) {
            cacheMapper.physicalDeleteById(row.getId());
        }
        vectorStoreService.deleteByEmbeddingIds(VEC_TABLE, vecIds);
        log.info("✓ 语义缓存淘汰: windowId={}, evicted={}", windowId, oldest.size());
    }

    private boolean enabled() {
        return !Boolean.FALSE.equals(ragProperties.getCache().getEnabled());
    }

    /** 取窗口第一个可用知识库的 embedding 模型；无知识库窗口不启用缓存 */
    private EmbeddingModel resolveEmbeddingModel(List<Long> kbIds) {
        for (Long kbId : kbIds) {
            KnowledgeBase kb = knowledgeBaseMapper.selectById(kbId);
            if (kb == null || kb.getEmbeddingModelId() == null) continue;
            EmbeddingModel model = modelCacheManager.getEmbeddingModel(kb.getEmbeddingModelId());
            if (model != null) return model;
        }
        return null;
    }

    private int resolveDimension(List<Long> kbIds) {
        for (Long kbId : kbIds) {
            KnowledgeBase kb = knowledgeBaseMapper.selectById(kbId);
            if (kb != null && kb.getEmbeddingModelId() != null && kb.getDimension() != null) {
                return kb.getDimension();
            }
        }
        return 1536;
    }
}
