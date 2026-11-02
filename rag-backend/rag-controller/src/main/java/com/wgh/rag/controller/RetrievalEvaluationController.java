package com.wgh.rag.controller;

import com.wgh.rag.common.result.Result;
import com.wgh.rag.config.property.RagProperties;
import com.wgh.rag.kb.entity.KbGroundTruth;
import com.wgh.rag.kb.service.RetrievalEvaluationService;
import com.wgh.rag.kb.service.RetrievalEvaluationService.GroundTruthPayload;
import com.wgh.rag.kb.service.RetrievalOptions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 检索评测：ground truth 标注维护 + 真实 Recall@K 计算
 */
@Tag(name = "检索评测")
@RestController
@RequestMapping("/api/kb")
@RequiredArgsConstructor
public class RetrievalEvaluationController {

    private final RetrievalEvaluationService evaluationService;
    private final RagProperties ragProperties;

    @Operation(summary = "列出标注")
    @GetMapping("/{kbId}/ground-truth")
    public Result<List<KbGroundTruth>> list(@PathVariable Long kbId) {
        return Result.success(evaluationService.list(kbId));
    }

    @Operation(summary = "新增标注（查询 + 相关 chunk 键 fileId:chunkIndex）")
    @PostMapping("/{kbId}/ground-truth")
    public Result<KbGroundTruth> create(@PathVariable Long kbId,
                                        @RequestBody GroundTruthPayload payload) {
        return Result.success(evaluationService.save(kbId, payload));
    }

    @Operation(summary = "批量导入标注（同查询覆盖，不重复）")
    @PostMapping("/{kbId}/ground-truth/import")
    public Result<Integer> importBatch(@PathVariable Long kbId,
                                       @RequestBody List<GroundTruthPayload> payloads) {
        return Result.success(evaluationService.importBatch(kbId, payloads).size());
    }

    @Operation(summary = "更新标注")
    @PutMapping("/{kbId}/ground-truth/{id}")
    public Result<KbGroundTruth> update(@PathVariable Long kbId, @PathVariable Long id,
                                        @RequestBody GroundTruthPayload payload) {
        return Result.success(evaluationService.update(kbId, id, payload));
    }

    @Operation(summary = "删除标注")
    @DeleteMapping("/{kbId}/ground-truth/{id}")
    public Result<Void> delete(@PathVariable Long kbId, @PathVariable Long id) {
        evaluationService.delete(kbId, id);
        return Result.success();
    }

    @Operation(summary = "列出知识库 chunk（标注相关 chunk 用）")
    @GetMapping("/{kbId}/chunks")
    public Result<Map<String, Object>> listChunks(
            @PathVariable Long kbId,
            @RequestParam(required = false) Long fileId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        return Result.success(evaluationService.listChunks(kbId, fileId, page, size));
    }

    @Operation(summary = "运行召回评测（真实 Recall@K = 命中标注数 / 标注总数）")
    @PostMapping("/{kbId}/evaluate")
    public Result<Map<String, Object>> evaluate(
            @PathVariable Long kbId,
            @RequestParam(required = false) Integer topK,
            @RequestParam(defaultValue = "false") boolean hybrid,
            @RequestParam(required = false) Long rerankModelId,
            @RequestParam(required = false) String queryTransformMode,
            @RequestParam(required = false) Long rewriteModelId) {
        int k = topK != null && topK > 0
                ? topK
                : (ragProperties.getRetrieve().getTopK() != null
                ? ragProperties.getRetrieve().getTopK() : 5);
        RetrievalOptions options = new RetrievalOptions(
                rerankModelId, hybrid, queryTransformMode, rewriteModelId, k);
        return Result.success(evaluationService.evaluate(kbId, options));
    }
}
