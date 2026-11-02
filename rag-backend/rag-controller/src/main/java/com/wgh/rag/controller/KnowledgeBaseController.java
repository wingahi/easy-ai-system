package com.wgh.rag.controller;

import com.wgh.rag.common.annotation.Audit;
import com.wgh.rag.common.result.PageResult;
import com.wgh.rag.common.result.Result;
import com.wgh.rag.kb.entity.KnowledgeBase;
import com.wgh.rag.kb.entity.KnowledgeFile;
import com.wgh.rag.kb.service.KnowledgeBaseService;
import com.wgh.rag.kb.service.KnowledgeFileService;
import com.wgh.rag.kb.service.RagRetrieveService;
import com.wgh.rag.kb.service.RetrievalQuality;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 知识库管理
 */
@Tag(name = "知识库管理")
@RestController
@RequestMapping("/api/kb")
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeBaseService kbService;
    private final KnowledgeFileService fileService;
    private final RagRetrieveService retrieveService;
    private final com.wgh.rag.config.property.RagProperties ragProperties;

    // ===== 知识库 CRUD =====

    @Operation(summary = "分页查询知识库")
    @GetMapping
    public Result<PageResult<KnowledgeBase>> page(
            @RequestParam(defaultValue = "1") Long page,
            @RequestParam(defaultValue = "10") Long size,
            @RequestParam(required = false) String keyword) {
        return Result.success(kbService.page(page, size, keyword));
    }

    @Operation(summary = "获取知识库详情")
    @GetMapping("/{id}")
    public Result<KnowledgeBase> getById(@PathVariable Long id) {
        return Result.success(kbService.getById(id));
    }

    @Operation(summary = "新增知识库")
    @PostMapping
    @Audit(action = "KB_CREATE", resourceType = "KNOWLEDGE_BASE")
    public Result<KnowledgeBase> create(@Valid @RequestBody KnowledgeBase kb) {
        return Result.success(kbService.create(kb));
    }

    @Operation(summary = "编辑知识库")
    @PutMapping
    @Audit(action = "KB_UPDATE", resourceType = "KNOWLEDGE_BASE")
    public Result<KnowledgeBase> update(@Valid @RequestBody KnowledgeBase kb) {
        return Result.success(kbService.update(kb));
    }

    @Operation(summary = "删除知识库")
    @DeleteMapping("/{id}")
    @Audit(action = "KB_DELETE", resourceType = "KNOWLEDGE_BASE")
    public Result<Void> delete(@PathVariable Long id) {
        kbService.delete(id);
        return Result.success();
    }

    @Operation(summary = "列出所有知识库（下拉选）")
    @GetMapping("/list")
    public Result<List<KnowledgeBase>> listAll() {
        return Result.success(kbService.listAll());
    }

    // ===== 文件管理 =====

    @Operation(summary = "上传文件到知识库")
    @PostMapping("/{kbId}/files")
    @Audit(action = "KB_FILE_UPLOAD", resourceType = "KNOWLEDGE_FILE")
    public Result<KnowledgeFile> uploadFile(
            @PathVariable Long kbId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "splitStrategy", defaultValue = "PARAGRAPH") String splitStrategy,
            @RequestParam(value = "splitConfig", required = false) String splitConfig) {
        return Result.success(fileService.upload(kbId, file, splitStrategy, splitConfig));
    }

    @Operation(summary = "列出知识库文件")
    @GetMapping("/{kbId}/files")
    public Result<List<KnowledgeFile>> listFiles(@PathVariable Long kbId) {
        return Result.success(fileService.listByKbId(kbId));
    }

    @Operation(summary = "删除知识库文件")
    @DeleteMapping("/files/{fileId}")
    @Audit(action = "KB_FILE_DELETE", resourceType = "KNOWLEDGE_FILE")
    public Result<Void> deleteFile(@PathVariable Long fileId) {
        fileService.delete(fileId);
        return Result.success();
    }

    @Operation(summary = "重新分割文件")
    @PostMapping("/files/{fileId}/resplit")
    public Result<KnowledgeFile> reSplitFile(
            @PathVariable Long fileId,
            @RequestParam String splitStrategy,
            @RequestParam(value = "splitConfig", required = false) String splitConfig) {
        return Result.success(fileService.reSplit(fileId, splitStrategy, splitConfig));
    }

    @Operation(summary = "预览文件分割情况")
    @GetMapping("/files/{fileId}/preview")
    public Result<Map<String, Object>> previewSplit(@PathVariable Long fileId) {
        return Result.success(fileService.previewSplit(fileId));
    }

    // ===== RAG 检索测试 =====

    @Operation(summary = "知识库搜索测试")
    @GetMapping("/{kbId}/search")
    public Result<java.util.Map<String, Object>> search(
            @PathVariable Long kbId,
            @RequestParam String query,
            @RequestParam(value = "rerankModelId", required = false) Long rerankModelId,
            @RequestParam(value = "hybrid", required = false, defaultValue = "false") boolean hybrid,
            @RequestParam(value = "queryTransformMode", required = false) String queryTransformMode,
            @RequestParam(value = "rewriteModelId", required = false) Long rewriteModelId,
            @RequestParam(value = "topK", required = false) Integer topK) {
        int effectiveTopK = topK != null && topK > 0
                ? topK
                : (ragProperties.getRetrieve().getTopK() != null ? ragProperties.getRetrieve().getTopK() : 5);
        com.wgh.rag.kb.service.RetrieveResult retrieveResult = retrieveService.searchMultiple(
                List.of(kbId), query, new com.wgh.rag.kb.service.RetrievalOptions(
                        rerankModelId, hybrid, queryTransformMode, rewriteModelId, effectiveTopK));
        List<dev.langchain4j.store.embedding.EmbeddingMatch<dev.langchain4j.data.segment.TextSegment>> matches =
                retrieveResult.matches();

        // 分数语义由检索层给出：只有 COSINE / RERANK 可与 minScore 阈值比较
        double minScore = ragProperties.getRetrieve().getMinScore() != null
                ? ragProperties.getRetrieve().getMinScore() : 0.55;
        String scoreType = retrieveResult.scoreType().name();
        boolean thresholdComparable = retrieveResult.scoreType() != com.wgh.rag.kb.service.RetrieveResult.ScoreType.RRF;

        List<java.util.Map<String, Object>> results = new java.util.ArrayList<>();
        for (var m : matches) {
            java.util.Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("score", m.score());
            // RRF 融合分数无法与余弦阈值比较，此时检索结果均已通过各自召回路的阈值过滤，视为命中
            item.put("hit", !thresholdComparable || (m.score() != null && m.score() >= minScore));
            item.put("content", m.embedded() != null ? m.embedded().text() : null);
            item.put("kbName", getMeta(m, "kbName"));
            item.put("fileId", getMetaInt(m, "fileId"));
            item.put("fileName", getMeta(m, "fileName"));
            item.put("chunkIndex", getMetaInt(m, "chunkIndex"));
            // 召回来源：KEYWORD 表示仅关键词路命中（向量路未召回）
            item.put("source", m.embeddingId() != null && retrieveResult.keywordHitIds().contains(m.embeddingId())
                    ? "KEYWORD" : "VECTOR");
            results.add(item);
        }

        long hitCount = results.stream().filter(r -> (Boolean) r.get("hit")).count();
        java.util.Map<String, Object> resp = new java.util.LinkedHashMap<>();
        resp.put("results", results);
        resp.put("totalMatches", matches.size());
        resp.put("rerankApplied", retrieveResult.rerankApplied());
        resp.put("rerankLatencyMs", retrieveResult.rerankLatencyMs());
        resp.put("hybridApplied", retrieveResult.hybridApplied());
        resp.put("keywordHitCount", retrieveResult.keywordHitIds().size());
        resp.put("transformedQueries", retrieveResult.transformedQueries());
        resp.put("transformLatencyMs", retrieveResult.transformLatencyMs());
        resp.put("filteredMatches", hitCount);
        resp.put("scoreType", scoreType);
        resp.put("relevantThreshold", thresholdComparable ? minScore : null);
        // 指标：在线无相关性标注，Recall@K 不可计算（需 ground truth），故只返回覆盖率与命中率
        resp.put("coverageRate", round4(RetrievalQuality.coverage(matches.size(), effectiveTopK)));
        resp.put("hitRate", round4(RetrievalQuality.hitRate((int) hitCount, matches.size())));
        resp.put("recallRate", null);
        return Result.success(resp);
    }

    private String getMeta(dev.langchain4j.store.embedding.EmbeddingMatch<dev.langchain4j.data.segment.TextSegment> m, String key) {
        if (m.embedded() == null || m.embedded().metadata() == null) return null;
        try { return m.embedded().metadata().getString(key); } catch (Exception e) { return null; }
    }

    private Integer getMetaInt(dev.langchain4j.store.embedding.EmbeddingMatch<dev.langchain4j.data.segment.TextSegment> m, String key) {
        String v = getMeta(m, key);
        if (v == null) return null;
        try { return Integer.parseInt(v); } catch (Exception e) { return null; }
    }

    /** 保留 4 位小数 */
    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
