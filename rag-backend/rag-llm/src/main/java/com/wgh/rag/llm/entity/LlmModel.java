package com.wgh.rag.llm.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.wgh.rag.common.entity.BaseEntity;
import com.wgh.rag.common.handler.BooleanToSmallintTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * LLM 模型配置实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wgh_llm_model")
public class LlmModel extends BaseEntity {

    /**
     * 模型名称（业务标识）
     */
    private String name;

    /**
     * 模型类型：CHAT / EMBEDDING
     */
    private String modelType;

    /**
     * 提供商：OPENAI / DASHSCOPE / DEEPSEEK / ANTHROPIC / CUSTOM
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
     * 上下文最大 Token
     */
    private Integer maxContextTokens;

    /**
     * 温度参数
     */
    private Double temperature;

    /**
     * 是否为压缩上下文模型（仅能选一个）
     */
    @TableField(typeHandler = BooleanToSmallintTypeHandler.class)
    private Boolean compressionModel;

    /**
     * 状态：0-禁用，1-启用
     */
    private Integer status;

    /**
     * 备注
     */
    private String remark;
}
