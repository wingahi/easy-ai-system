package com.wgh.rag.controller;

import com.wgh.rag.auth.constant.AuthConstants;
import com.wgh.rag.log.annotation.Audit;
import com.wgh.rag.common.result.PageResult;
import com.wgh.rag.common.result.Result;
import com.wgh.rag.common.security.RequiresPermission;
import com.wgh.rag.llm.entity.ModelPrice;
import com.wgh.rag.llm.service.ModelPriceService;
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

/**
 * 模型价格管理（成本统计的价格配置）
 */
@Tag(name = "模型价格管理")
@RestController
@RequestMapping("/api/admin/model-prices")
@RequiredArgsConstructor
@RequiresPermission(AuthConstants.PERM_PRICING_MANAGE)
public class ModelPriceController {

    private final ModelPriceService modelPriceService;

    @Operation(summary = "分页查询模型价格")
    @GetMapping
    public Result<PageResult<ModelPrice>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String keyword) {
        return Result.success(modelPriceService.page(page, size, keyword));
    }

    @Operation(summary = "新增模型价格")
    @PostMapping
    @Audit(action = "MODEL_PRICE_CREATE", resourceType = "MODEL_PRICE")
    public Result<ModelPrice> create(@RequestBody ModelPrice price) {
        return Result.success(modelPriceService.create(price));
    }

    @Operation(summary = "编辑模型价格")
    @PutMapping
    @Audit(action = "MODEL_PRICE_UPDATE", resourceType = "MODEL_PRICE")
    public Result<ModelPrice> update(@RequestBody ModelPrice price) {
        return Result.success(modelPriceService.update(price));
    }

    @Operation(summary = "删除模型价格")
    @DeleteMapping("/{id}")
    @Audit(action = "MODEL_PRICE_DELETE", resourceType = "MODEL_PRICE")
    public Result<Void> delete(@PathVariable Long id) {
        modelPriceService.delete(id);
        return Result.success();
    }
}
