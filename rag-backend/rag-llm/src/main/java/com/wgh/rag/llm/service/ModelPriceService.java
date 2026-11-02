package com.wgh.rag.llm.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.result.PageResult;
import com.wgh.rag.llm.entity.ModelPrice;
import com.wgh.rag.llm.mapper.ModelPriceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 模型价格服务：价格 CRUD + 成本估算
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelPriceService {

    private final ModelPriceMapper modelPriceMapper;

    public PageResult<ModelPrice> page(long page, long size, String keyword) {
        Page<ModelPrice> p = new Page<>(page, size);
        modelPriceMapper.selectPage(p, new LambdaQueryWrapper<ModelPrice>()
                .like(keyword != null && !keyword.isBlank(), ModelPrice::getModelName, keyword)
                .orderByAsc(ModelPrice::getProvider)
                .orderByAsc(ModelPrice::getModelName));
        return new PageResult<>(p.getTotal(), p.getCurrent(), p.getSize(), p.getRecords());
    }

    public ModelPrice create(ModelPrice price) {
        validate(price);
        ModelPrice existing = findByProviderAndModel(price.getProvider(), price.getModelName());
        if (existing != null) {
            throw new BusinessException("该厂商下此模型的价格已存在（id=" + existing.getId() + "），请使用编辑");
        }
        if (price.getCurrency() == null || price.getCurrency().isBlank()) {
            price.setCurrency("CNY");
        }
        modelPriceMapper.insert(price);
        return price;
    }

    public ModelPrice update(ModelPrice price) {
        if (price.getId() == null) {
            throw new BusinessException("id 不能为空");
        }
        validate(price);
        ModelPrice existing = findByProviderAndModel(price.getProvider(), price.getModelName());
        if (existing != null && !existing.getId().equals(price.getId())) {
            throw new BusinessException("该厂商下此模型的价格已存在（id=" + existing.getId() + "）");
        }
        modelPriceMapper.updateById(price);
        return modelPriceMapper.selectById(price.getId());
    }

    public void delete(Long id) {
        modelPriceMapper.deleteById(id);
    }

    public ModelPrice findByProviderAndModel(String provider, String modelName) {
        return modelPriceMapper.selectOne(new LambdaQueryWrapper<ModelPrice>()
                .eq(ModelPrice::getProvider, provider)
                .eq(ModelPrice::getModelName, modelName)
                .last("LIMIT 1"));
    }

    /**
     * 估算成本（元）。无价格配置返回 null。
     */
    public Double estimateCost(String provider, String modelName, long inputTokens, long outputTokens) {
        if (provider == null || modelName == null) return null;
        ModelPrice price = findByProviderAndModel(provider, modelName);
        if (price == null) return null;
        double in = price.getInputPricePer1k() != null ? price.getInputPricePer1k() : 0.0;
        double out = price.getOutputPricePer1k() != null ? price.getOutputPricePer1k() : 0.0;
        return in * inputTokens / 1000.0 + out * outputTokens / 1000.0;
    }

    private void validate(ModelPrice price) {
        if (price.getProvider() == null || price.getProvider().isBlank()) {
            throw new BusinessException("provider 不能为空");
        }
        if (price.getModelName() == null || price.getModelName().isBlank()) {
            throw new BusinessException("modelName 不能为空");
        }
        if (price.getInputPricePer1k() != null && price.getInputPricePer1k() < 0) {
            throw new BusinessException("输入单价不能为负");
        }
        if (price.getOutputPricePer1k() != null && price.getOutputPricePer1k() < 0) {
            throw new BusinessException("输出单价不能为负");
        }
    }
}
