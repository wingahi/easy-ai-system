package com.wgh.rag.controller;

import com.wgh.rag.common.result.PageResult;
import com.wgh.rag.common.result.Result;
import com.wgh.rag.llm.entity.EmbeddingModelConfig;
import com.wgh.rag.llm.entity.LlmModel;
import com.wgh.rag.llm.service.LlmModelService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * LLM 模型管理
 */
@Tag(name = "模型管理")
@RestController
@RequestMapping("/api/models")
@RequiredArgsConstructor
public class LlmModelController {

    private final LlmModelService modelService;

    @Operation(summary = "分页查询 ChatModel")
    @GetMapping("/chat")
    public Result<PageResult<LlmModel>> pageChatModels(
            @RequestParam(defaultValue = "1") Long page,
            @RequestParam(defaultValue = "10") Long size) {
        return Result.success(modelService.pageChatModels(page, size));
    }

    @Operation(summary = "获取 ChatModel 详情")
    @GetMapping("/chat/{id}")
    public Result<LlmModel> getChatModel(@PathVariable Long id) {
        return Result.success(modelService.getChatModel(id));
    }

    @Operation(summary = "新增 ChatModel")
    @PostMapping("/chat")
    public Result<LlmModel> createChatModel(@Valid @RequestBody LlmModel model) {
        return Result.success(modelService.createChatModel(model));
    }

    @Operation(summary = "编辑 ChatModel")
    @PutMapping("/chat")
    public Result<LlmModel> updateChatModel(@Valid @RequestBody LlmModel model) {
        return Result.success(modelService.updateChatModel(model));
    }

    @Operation(summary = "删除 ChatModel")
    @DeleteMapping("/chat/{id}")
    public Result<Void> deleteChatModel(@PathVariable Long id) {
        modelService.deleteChatModel(id);
        return Result.success();
    }

    @Operation(summary = "列出所有启用的 ChatModel")
    @GetMapping("/chat/list")
    public Result<List<LlmModel>> listEnabledChatModels() {
        return Result.success(modelService.listEnabledChatModels());
    }

    // ===== Embedding =====

    @Operation(summary = "分页查询嵌入模型")
    @GetMapping("/embedding")
    public Result<PageResult<EmbeddingModelConfig>> pageEmbeddingModels(
            @RequestParam(defaultValue = "1") Long page,
            @RequestParam(defaultValue = "10") Long size) {
        return Result.success(modelService.pageEmbeddingModels(page, size));
    }

    @Operation(summary = "新增嵌入模型")
    @PostMapping("/embedding")
    public Result<EmbeddingModelConfig> createEmbeddingModel(@Valid @RequestBody EmbeddingModelConfig model) {
        return Result.success(modelService.createEmbeddingModel(model));
    }

    @Operation(summary = "编辑嵌入模型")
    @PutMapping("/embedding")
    public Result<EmbeddingModelConfig> updateEmbeddingModel(@Valid @RequestBody EmbeddingModelConfig model) {
        return Result.success(modelService.updateEmbeddingModel(model));
    }

    @Operation(summary = "删除嵌入模型")
    @DeleteMapping("/embedding/{id}")
    public Result<Void> deleteEmbeddingModel(@PathVariable Long id) {
        modelService.deleteEmbeddingModel(id);
        return Result.success();
    }

    @Operation(summary = "列出所有启用的嵌入模型")
    @GetMapping("/embedding/list")
    public Result<List<EmbeddingModelConfig>> listEnabledEmbeddingModels() {
        return Result.success(modelService.listEnabledEmbeddingModels());
    }
}
