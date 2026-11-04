package com.wgh.rag.llm.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.wgh.rag.data.entity.BaseEntity;
import com.wgh.rag.data.handler.BooleanToSmallintTypeHandler;
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
     * <p>
     * 仅允许反序列化（新增/编辑时提交），永不参与序列化输出。
     */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
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
