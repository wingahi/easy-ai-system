package com.wgh.rag.controller;

import com.wgh.rag.common.result.PageResult;
import com.wgh.rag.common.result.Result;
import com.wgh.rag.kb.entity.KnowledgeBase;
import com.wgh.rag.kb.entity.KnowledgeFile;
import com.wgh.rag.kb.service.KnowledgeBaseService;
import com.wgh.rag.kb.service.KnowledgeFileService;
import com.wgh.rag.kb.service.RagRetrieveService;
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
    public Result<KnowledgeBase> create(@Valid @RequestBody KnowledgeBase kb) {
        return Result.success(kbService.create(kb));
    }

    @Operation(summary = "编辑知识库")
    @PutMapping
    public Result<KnowledgeBase> update(@Valid @RequestBody KnowledgeBase kb) {
        return Result.success(kbService.update(kb));
    }

    @Operation(summary = "删除知识库")
    @DeleteMapping("/{id}")
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
            @RequestParam String query) {
        List<dev.langchain4j.store.embedding.EmbeddingMatch<dev.langchain4j.data.segment.TextSegment>> matches =
                retrieveService.searchMultiple(List.of(kbId), query);

        List<java.util.Map<String, Object>> results = new java.util.ArrayList<>();
        for (var m : matches) {
            java.util.Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("score", m.score());
            item.put("hit", m.score() >= 0.65);
            item.put("content", m.embedded() != null ? m.embedded().text() : null);
            item.put("kbName", getMeta(m, "kbName"));
            item.put("fileName", getMeta(m, "fileName"));
            item.put("chunkIndex", getMetaInt(m, "chunkIndex"));
            results.add(item);
        }

        java.util.Map<String, Object> resp = new java.util.LinkedHashMap<>();
        resp.put("results", results);
        resp.put("totalMatches", matches.size());
        resp.put("filteredMatches", results.stream().filter(r -> (Boolean) r.get("hit")).count());
        // 评估指标：简化计算（无 ground truth 时做 best-effort）
        double recallRate = matches.isEmpty() ? 0
                : results.stream().filter(r -> (Boolean) r.get("hit")).count() / (double) matches.size();
        resp.put("recallRate", Math.round(recallRate * 10000.0) / 10000.0);
        resp.put("hitRate", results.isEmpty() ? 0
                : Math.round((results.stream().filter(r -> (Boolean) r.get("hit")).count()
                        / (double) results.size()) * 10000.0) / 10000.0);
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
}
