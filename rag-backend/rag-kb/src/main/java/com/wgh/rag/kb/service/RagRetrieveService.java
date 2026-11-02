package com.wgh.rag.kb.service;

import com.wgh.rag.config.property.RagProperties;
import com.wgh.rag.kb.entity.KnowledgeBase;
import com.wgh.rag.kb.mapper.KnowledgeBaseMapper;
import com.wgh.rag.llm.factory.ModelCacheManager;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * RAG 检索服务 - 封装 VectorStoreService.search()（pgvector 向量检索）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagRetrieveService {

    /** Rerank 开启时的候选放大倍数与上限 */
    private static final int RERANK_CANDIDATE_FACTOR = 3;
    private static final int RERANK_CANDIDATE_MAX = 30;

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final ModelCacheManager modelCacheManager;
    private final VectorStoreService vectorStoreService;
    private final RagProperties ragProperties;
    private final RerankService rerankService;

    /**
     * 多知识库联合检索（默认配置，不做增强）
     */
    public List<EmbeddingMatch<TextSegment>> searchMultiple(List<Long> kbIds, String queryText) {
        return searchMultiple(kbIds, queryText, RetrievalOptions.defaults()).matches();
    }

    /**
     * 多知识库联合检索 - 按 embeddingModelId 分组（不同模型维度不同，不能共享 query 向量）。
     * 开启 Rerank 时：向量候选放大至 min(topK*3, 30)，重排序后取 topK；失败降级为原始向量序。
     */
    public RetrieveResult searchMultiple(List<Long> kbIds, String queryText, RetrievalOptions options) {
        if (kbIds == null || kbIds.isEmpty()) return RetrieveResult.empty();
        RetrievalOptions opts = options != null ? options : RetrievalOptions.defaults();

        RagProperties.Retrieve retrieve = ragProperties.getRetrieve();
        int topK = retrieve.getTopK() != null ? retrieve.getTopK() : 5;
        // minScore 默认 0.55：cosine 相似度下 0.3 噪声过多
        double minScore = retrieve.getMinScore() != null ? retrieve.getMinScore() : 0.55;
        int candidateTopN = opts.rerankEnabled() ? Math.min(topK * RERANK_CANDIDATE_FACTOR, RERANK_CANDIDATE_MAX) : topK;

        // 按 embeddingModelId 分组
        java.util.Map<Long, List<KnowledgeBase>> byModel = new java.util.LinkedHashMap<>();
        for (Long kbId : kbIds) {
            KnowledgeBase kb = knowledgeBaseMapper.selectById(kbId);
            if (kb == null || kb.getCollectionName() == null || kb.getEmbeddingModelId() == null) continue;
            byModel.computeIfAbsent(kb.getEmbeddingModelId(), k -> new ArrayList<>()).add(kb);
        }
        if (byModel.isEmpty()) {
            log.warn("searchMultiple: 未找到可用的 EmbeddingModel, kbIds={}", kbIds);
            return RetrieveResult.empty();
        }

        List<EmbeddingMatch<TextSegment>> allMatches = new ArrayList<>();
        for (var entry : byModel.entrySet()) {
            EmbeddingModel embeddingModel = modelCacheManager.getEmbeddingModel(entry.getKey());
            if (embeddingModel == null) {
                log.warn("EmbeddingModel id={} 不可用，跳过 {} 个知识库", entry.getKey(), entry.getValue().size());
                continue;
            }
            Embedding queryEmbedding = embeddingModel.embed(queryText).content();
            for (KnowledgeBase kb : entry.getValue()) {
                try {
                    EmbeddingStore<TextSegment> store = vectorStoreService.createEmbeddingStore(
                            kb.getCollectionName(),
                            kb.getDimension() != null ? kb.getDimension() : 1536);
                    allMatches.addAll(search(store, queryEmbedding, candidateTopN, minScore));
                } catch (Exception e) {
                    log.error("搜索知识库 {} 失败", kb.getId(), e);
                }
            }
        }

        // 按分数降序
        allMatches.sort((a, b) -> {
            Double sa = a.score();
            Double sb = b.score();
            return Double.compare(sb != null ? sb : 0.0, sa != null ? sa : 0.0);
        });

        if (!opts.rerankEnabled()) {
            return new RetrieveResult(allMatches.stream().limit(topK).toList(), false, 0);
        }
        return applyRerank(allMatches.stream().limit(candidateTopN).toList(), queryText, opts.rerankModelId(), topK);
    }

    /**
     * Rerank 重排序：候选 → RERANK 模型 → 按新分数取 topK；任何异常降级为原始向量序
     */
    private RetrieveResult applyRerank(List<EmbeddingMatch<TextSegment>> candidates, String queryText,
                                       Long rerankModelId, int topK) {
        if (candidates.size() <= 1) {
            return new RetrieveResult(candidates.stream().limit(topK).toList(), false, 0);
        }
        long start = System.currentTimeMillis();
        try {
            List<TextSegment> segments = candidates.stream().map(EmbeddingMatch::embedded).toList();
            List<Double> scores = rerankService.scoreAll(segments, queryText, rerankModelId);
            List<EmbeddingMatch<TextSegment>> reranked = new ArrayList<>(candidates.size());
            for (int i = 0; i < candidates.size(); i++) {
                EmbeddingMatch<TextSegment> m = candidates.get(i);
                double score = i < scores.size() ? scores.get(i) : 0.0;
                reranked.add(new EmbeddingMatch<>(score, m.embeddingId(), m.embedding(), m.embedded()));
            }
            reranked.sort((a, b) -> Double.compare(
                    b.score() != null ? b.score() : 0.0,
                    a.score() != null ? a.score() : 0.0));
            long latency = System.currentTimeMillis() - start;
            log.info("Rerank 完成: modelId={}, 候选={}, 耗时={}ms", rerankModelId, candidates.size(), latency);
            return new RetrieveResult(reranked.stream().limit(topK).toList(), true, latency);
        } catch (Exception e) {
            log.warn("Rerank 失败，降级为原始向量序: {}", e.getMessage());
            return new RetrieveResult(candidates.stream().limit(topK).toList(), false,
                    System.currentTimeMillis() - start);
        }
    }

    /**
     * 执行向量相似度搜索
     */
    public List<EmbeddingMatch<TextSegment>> search(EmbeddingStore<TextSegment> store,
                                                    Embedding queryEmbedding,
                                                    int maxResults,
                                                    double minScore) {
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(maxResults)
                .minScore(minScore)
                .build();

        EmbeddingSearchResult<TextSegment> result = store.search(request);
        return result.matches();
    }

    /**
     * 判断 match 是否有效（score > minScore）
     */
    public boolean isHit(EmbeddingMatch<TextSegment> match, double minScore) {
        return match.score() != null && match.score() >= minScore;
    }

    /**
     * 安全获取 metadata 字符串
     */
    public String getMetadataString(TextSegment segment, String key) {
        if (segment == null || segment.metadata() == null) return null;
        return segment.metadata().getString(key);
    }
}
