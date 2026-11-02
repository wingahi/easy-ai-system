package com.wgh.rag.controller;

import com.wgh.rag.kb.entity.Tag;
import com.wgh.rag.kb.service.FileVersionService;
import com.wgh.rag.kb.service.TagService;
import com.wgh.rag.common.annotation.Audit;
import com.wgh.rag.common.result.PageResult;
import com.wgh.rag.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 标签与知识库文件版本
 */
@io.swagger.v3.oas.annotations.tags.Tag(name = "标签 / 版本")
@RestController
@RequestMapping
@RequiredArgsConstructor
public class TagController {

    private final TagService tagService;
    private final FileVersionService versionService;

    // ===== 标签 CRUD =====

    @Operation(summary = "分页查询标签")
    @GetMapping("/api/tags")
    public Result<PageResult<Tag>> page(
            @RequestParam(defaultValue = "1") Long page,
            @RequestParam(defaultValue = "10") Long size,
            @RequestParam(required = false) String keyword) {
        return Result.success(tagService.page(page, size, keyword));
    }

    @Operation(summary = "全量标签")
    @GetMapping("/api/tags/list")
    public Result<List<Tag>> listAll() {
        return Result.success(tagService.listAll());
    }

    @Operation(summary = "标签详情")
    @GetMapping("/api/tags/{id}")
    public Result<Tag> get(@PathVariable Long id) {
        return Result.success(tagService.getById(id));
    }

    @Operation(summary = "新增标签")
    @PostMapping("/api/tags")
    @Audit(action = "TAG_CREATE", resourceType = "TAG")
    public Result<Tag> create(@RequestBody Tag tag) {
        return Result.success(tagService.create(tag));
    }

    @Operation(summary = "更新标签")
    @PutMapping("/api/tags")
    @Audit(action = "TAG_UPDATE", resourceType = "TAG")
    public Result<Tag> update(@RequestBody Tag tag) {
        return Result.success(tagService.update(tag));
    }

    @Operation(summary = "删除标签")
    @DeleteMapping("/api/tags/{id}")
    @Audit(action = "TAG_DELETE", resourceType = "TAG")
    public Result<Void> delete(@PathVariable Long id) {
        tagService.delete(id);
        return Result.success();
    }

    // ===== 知识库-标签绑定 =====

    @Operation(summary = "设置知识库标签（完整覆盖）")
    @PutMapping("/api/kb/{kbId}/tags")
    @Audit(action = "KB_TAG_SET", resourceType = "KNOWLEDGE_BASE")
    public Result<Void> setKbTags(@PathVariable Long kbId, @RequestBody Map<String, Object> body) {
        List<Long> tagIds = (List<Long>) body.get("tagIds");
        tagService.setKbTags(kbId, tagIds);
        return Result.success();
    }

    @Operation(summary = "列出知识库已绑定标签")
    @GetMapping("/api/kb/{kbId}/tags")
    public Result<List<Tag>> listKbTags(@PathVariable Long kbId) {
        return Result.success(tagService.listKbTags(kbId));
    }

    // ===== 文件-标签绑定 =====

    @Operation(summary = "设置文件标签（完整覆盖）")
    @PutMapping("/api/kb/files/{fileId}/tags")
    @Audit(action = "FILE_TAG_SET", resourceType = "KNOWLEDGE_FILE")
    public Result<Void> setFileTags(@PathVariable Long fileId, @RequestBody Map<String, Object> body) {
        List<Long> tagIds = (List<Long>) body.get("tagIds");
        tagService.setFileTags(fileId, tagIds);
        return Result.success();
    }

    @Operation(summary = "列出文件已绑定标签")
    @GetMapping("/api/kb/files/{fileId}/tags")
    public Result<List<Tag>> listFileTags(@PathVariable Long fileId) {
        return Result.success(tagService.listFileTags(fileId));
    }

    // ===== 版本快照 =====

    @Operation(summary = "列出文件历史版本快照")
    @GetMapping("/api/kb/files/{fileId}/versions")
    public Result<List<Map<String, Object>>> listVersions(@PathVariable Long fileId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var v : versionService.list(fileId)) {
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("id", v.getId());
            row.put("fileId", v.getFileId());
            row.put("version", v.getVersion());
            row.put("chunkCount", v.getChunkCount() != null ? v.getChunkCount() : 0);
            row.put("splitStrategy", v.getSplitStrategy() != null ? v.getSplitStrategy() : "");
            row.put("status", v.getStatus() != null ? v.getStatus() : "");
            row.put("createTime", v.getCreateTime());
            rows.add(row);
        }
        return Result.success(rows);
    }

    @Operation(summary = "回滚到指定版本（按该版本切分策略重切）")
    @PostMapping("/api/kb/files/{fileId}/rollback/{version}")
    @Audit(action = "FILE_ROLLBACK", resourceType = "KNOWLEDGE_FILE")
    public Result<Void> rollback(@PathVariable Long fileId, @PathVariable Integer version) {
        versionService.rollback(fileId, version);
        return Result.success();
    }
}
