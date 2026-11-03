package com.wgh.rag.llm.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.wgh.rag.data.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 模型价格表（每 1K token 单价，用于成本估算）
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wgh_model_price")
public class ModelPrice extends BaseEntity {

    /**
     * 提供厂商（与 wgh_llm_model.provider 对齐）
     */
    private String provider;

    /**
     * 厂商侧模型名（与 wgh_llm_model.model_name 对齐）
     */
    private String modelName;

    /**
     * 输入单价（元/1K tokens）
     */
    @TableField("input_price_per_1k")
    private Double inputPricePer1k;

    /**
     * 输出单价（元/1K tokens）
     */
    @TableField("output_price_per_1k")
    private Double outputPricePer1k;

    /**
     * 币种（默认 CNY）
     */
    private String currency;

    /**
     * 备注
     */
    private String remark;
}
