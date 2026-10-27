package com.wgh.rag.llm.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.wgh.rag.common.entity.BaseEntity;
import com.wgh.rag.common.handler.BooleanToSmallintTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 嵌入模型配置实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wgh_embedding_model")
public class EmbeddingModelConfig extends BaseEntity {

    /**
     * 名称（业务标识）
     */
    private String name;

    /**
     * 提供商：OPENAI / DASHSCOPE / LOCAL / CUSTOM
     */
    private String provider;

    /**
     * 厂商模型名称
     */
    private String modelName;

    /**
     * API Base URL
     */
    private String baseUrl;

    /**
     * API Key
     */
    private String apiKey;

    /**
     * 向量维度
     */
    private Integer dimension;

    /**
     * 状态：0-禁用，1-启用
     */
    private Integer status;

    /**
     * 是否为默认嵌入模型
     */
    @TableField(typeHandler = BooleanToSmallintTypeHandler.class)
    private Boolean isDefault;

    /**
     * 备注
     */
    private String remark;
}
