package com.wgh.rag.controller.eval;

import com.wgh.rag.auth.constant.AuthConstants;
import com.wgh.rag.common.result.Result;
import com.wgh.rag.common.security.RequiresPermission;
import com.wgh.rag.config.property.RagProperties;
import com.wgh.rag.kb.entity.EvalCase;
import com.wgh.rag.kb.entity.EvalDataset;
import com.wgh.rag.kb.entity.EvalResult;
import com.wgh.rag.kb.service.EvalDatasetService;
import com.wgh.rag.kb.service.EvalDatasetService.CasePayload;
import com.wgh.rag.kb.service.EvalDatasetService.DatasetPayload;
import com.wgh.rag.kb.service.RetrievalOptions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 评测数据集（版本化）：数据集/用例 CRUD、按版本评测、历史结果归档与对比
 */
@Tag(name = "评测数据集")
@RestController
@RequestMapping("/api/kb/{kbId}/eval-datasets")
@RequiredArgsConstructor
@RequiresPermission(AuthConstants.PERM_KB_READ)
public class EvalDatasetController {

    private final EvalDatasetService datasetService;
    private final RagProperties ragProperties;

    @Operation(summary = "列出评测数据集")
    @GetMapping
    public Result<List<EvalDataset>> list(@PathVariable Long kbId) {
        return Result.success(datasetService.list(kbId));
    }

    @Operation(summary = "创建评测数据集")
    @PostMapping
    @RequiresPermission(AuthConstants.PERM_KB_WRITE)
    public Result<EvalDataset> create(@PathVariable Long kbId, @RequestBody DatasetPayload payload) {
        return Result.success(datasetService.create(kbId, payload));
    }

    @Operation(summary = "更新评测数据集名称/说明（不产生新版本）")
    @PutMapping("/{datasetId}")
    @RequiresPermission(AuthConstants.PERM_KB_WRITE)
    public Result<EvalDataset> update(@PathVariable Long kbId, @PathVariable Long datasetId,
                                      @RequestBody DatasetPayload payload) {
        return Result.success(datasetService.update(kbId, datasetId, payload));
    }

    @Operation(summary = "删除评测数据集（用例与历史结果保留归档）")
    @DeleteMapping("/{datasetId}")
    @RequiresPermission(AuthConstants.PERM_KB_WRITE)
    public Result<Void> delete(@PathVariable Long kbId, @PathVariable Long datasetId) {
        datasetService.delete(kbId, datasetId);
        return Result.success();
    }

    @Operation(summary = "数据集版本号列表（升序）")
    @GetMapping("/{datasetId}/versions")
    public Result<List<Integer>> versions(@PathVariable Long kbId, @PathVariable Long datasetId) {
        return Result.success(datasetService.listVersions(kbId, datasetId));
    }

    @Operation(summary = "列出版本用例（version 为空取当前版本）")
    @GetMapping("/{datasetId}/cases")
    public Result<List<EvalCase>> listCases(@PathVariable Long kbId, @PathVariable Long datasetId,
                                            @RequestParam(required = false) Integer version) {
        return Result.success(datasetService.listCases(kbId, datasetId, version));
    }

    @Operation(summary = "新增用例（生成新版本）")
    @PostMapping("/{datasetId}/cases")
    @RequiresPermission(AuthConstants.PERM_KB_WRITE)
    public Result<EvalCase> addCase(@PathVariable Long kbId, @PathVariable Long datasetId,
                                    @RequestBody CasePayload payload) {
        return Result.success(datasetService.addCase(kbId, datasetId, payload));
    }

    @Operation(summary = "修改用例（生成新版本，旧版本只读）")
    @PutMapping("/{datasetId}/cases/{caseId}")
    @RequiresPermission(AuthConstants.PERM_KB_WRITE)
    public Result<EvalCase> updateCase(@PathVariable Long kbId, @PathVariable Long datasetId,
                                       @PathVariable Long caseId, @RequestBody CasePayload payload) {
        return Result.success(datasetService.updateCase(kbId, datasetId, caseId, payload));
    }

    @Operation(summary = "删除用例（生成新版本，旧版本只读）")
    @DeleteMapping("/{datasetId}/cases/{caseId}")
    @RequiresPermission(AuthConstants.PERM_KB_WRITE)
    public Result<Void> deleteCase(@PathVariable Long kbId, @PathVariable Long datasetId,
                                   @PathVariable Long caseId) {
        datasetService.deleteCase(kbId, datasetId, caseId);
        return Result.success();
    }

    @Operation(summary = "按版本运行评测并归档结果")
    @PostMapping("/{datasetId}/evaluate")
    @RequiresPermission(AuthConstants.PERM_KB_WRITE)
    public Result<Map<String, Object>> evaluate(@PathVariable Long kbId, @PathVariable Long datasetId,
                                                @RequestParam(required = false) Integer version,
                                                @RequestParam(required = false) Integer topK,
                                                @RequestParam(defaultValue = "false") boolean hybrid,
                                                @RequestParam(required = false) Long rerankModelId) {
        int k = topK != null && topK > 0
                ? topK
                : (ragProperties.getRetrieve().getTopK() != null
                ? ragProperties.getRetrieve().getTopK() : 5);
        RetrievalOptions options = new RetrievalOptions(rerankModelId, hybrid, null, null, k);
        return Result.success(datasetService.evaluate(kbId, datasetId, version, options));
    }

    @Operation(summary = "历史评测结果（按版本归档）")
    @GetMapping("/{datasetId}/results")
    public Result<List<EvalResult>> results(@PathVariable Long kbId, @PathVariable Long datasetId) {
        return Result.success(datasetService.listResults(kbId, datasetId));
    }

    @Operation(summary = "对比两次归档结果（指标差值 + 逐查询 recall 差值）")
    @GetMapping("/{datasetId}/results/compare")
    public Result<Map<String, Object>> compare(@PathVariable Long kbId, @PathVariable Long datasetId,
                                               @RequestParam Long baseId,
                                               @RequestParam Long targetId) {
        return Result.success(datasetService.compareResults(kbId, datasetId, baseId, targetId));
    }
}
