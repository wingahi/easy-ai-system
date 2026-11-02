package com.wgh.rag.llm.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.llm.entity.ModelPrice;
import com.wgh.rag.llm.mapper.ModelPriceMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * ModelPriceService 单元测试：成本估算 + 唯一性校验
 */
@ExtendWith(MockitoExtension.class)
class ModelPriceServiceTest {

    @Mock
    private ModelPriceMapper modelPriceMapper;

    @InjectMocks
    private ModelPriceService modelPriceService;

    private ModelPrice price(String provider, String model, double in, double out) {
        ModelPrice p = new ModelPrice();
        p.setProvider(provider);
        p.setModelName(model);
        p.setInputPricePer1k(in);
        p.setOutputPricePer1k(out);
        return p;
    }

    @Test
    @DisplayName("成本估算：输入输出分别计价")
    void estimateCost() {
        when(modelPriceMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(price("DEEPSEEK", "deepseek-chat", 0.001, 0.002));

        // 1000 输入 * 0.001/1k + 500 输出 * 0.002/1k = 0.001 + 0.001 = 0.002
        Double cost = modelPriceService.estimateCost("DEEPSEEK", "deepseek-chat", 1000, 500);

        assertThat(cost).isNotNull();
        assertThat(cost).isEqualTo(0.002, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    @DisplayName("无价格配置时返回 null")
    void estimateCostNoPrice() {
        when(modelPriceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        assertThat(modelPriceService.estimateCost("OPENAI", "gpt-x", 100, 100)).isNull();
    }

    @Test
    @DisplayName("参数缺失时返回 null")
    void estimateCostNullArgs() {
        assertThat(modelPriceService.estimateCost(null, "m", 1, 1)).isNull();
        assertThat(modelPriceService.estimateCost("p", null, 1, 1)).isNull();
    }

    @Test
    @DisplayName("新增时同厂商同模型已存在则拒绝")
    void createDuplicateRejected() {
        when(modelPriceMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(price("DEEPSEEK", "deepseek-chat", 0.001, 0.002));

        assertThatThrownBy(() -> modelPriceService.create(price("DEEPSEEK", "deepseek-chat", 0.01, 0.02)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已存在");
    }

    @Test
    @DisplayName("负单价校验失败")
    void negativePriceRejected() {
        assertThatThrownBy(() -> modelPriceService.create(price("P", "m", -1, 0)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能为负");
    }
}
