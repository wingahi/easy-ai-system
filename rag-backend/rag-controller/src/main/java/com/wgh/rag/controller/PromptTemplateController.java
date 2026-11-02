package com.wgh.rag.controller;

import com.wgh.rag.chat.entity.PromptTemplate;
import com.wgh.rag.chat.service.PromptTemplateService;
import com.wgh.rag.common.annotation.Audit;
import com.wgh.rag.common.result.PageResult;
import com.wgh.rag.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Prompt 模板中心
 */
@Tag(name = "Prompt 模板中心")
@RestController
@RequestMapping("/api/prompt-templates")
@RequiredArgsConstructor
public class PromptTemplateController {

    private final PromptTemplateService templateService;

    @Operation(summary = "分页查询模板")
    @GetMapping
    public Result<PageResult<PromptTemplate>> page(
            @RequestParam(defaultValue = "1") Long page,
            @RequestParam(defaultValue = "10") Long size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category) {
        return Result.success(templateService.page(page, size, keyword, category));
    }

    @Operation(summary = "全量模板列表（窗口表单下拉用）")
    @GetMapping("/list")
    public Result<List<PromptTemplate>> listAll() {
        return Result.success(templateService.listAll());
    }

    @Operation(summary = "模板详情")
    @GetMapping("/{id}")
    public Result<PromptTemplate> get(@PathVariable Long id) {
        return Result.success(templateService.getById(id));
    }

    @Operation(summary = "创建模板")
    @PostMapping
    @Audit(action = "PROMPT_TEMPLATE_CREATE", resourceType = "PROMPT_TEMPLATE")
    public Result<PromptTemplate> create(@RequestBody PromptTemplate template) {
        return Result.success(templateService.create(template));
    }

    @Operation(summary = "更新模板（内容变更自动升版本号）")
    @PutMapping
    @Audit(action = "PROMPT_TEMPLATE_UPDATE", resourceType = "PROMPT_TEMPLATE")
    public Result<PromptTemplate> update(@RequestBody PromptTemplate template) {
        return Result.success(templateService.update(template));
    }

    @Operation(summary = "删除模板（内置模板不可删）")
    @DeleteMapping("/{id}")
    @Audit(action = "PROMPT_TEMPLATE_DELETE", resourceType = "PROMPT_TEMPLATE")
    public Result<Void> delete(@PathVariable Long id) {
        templateService.delete(id);
        return Result.success();
    }

    @Operation(summary = "渲染预览（{{var}} 变量替换）")
    @PostMapping("/{id}/render")
    public Result<Map<String, String>> render(@PathVariable Long id,
                                              @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> vars = body != null && body.get("vars") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
        return Result.success(Map.of("rendered", templateService.renderById(id, vars)));
    }
}
