package com.wgh.rag.tool.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.wgh.rag.common.entity.BaseEntity;
import com.wgh.rag.common.handler.BooleanToSmallintTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 工具配置实体（Skill / MCP / HTTP接口 / Function Calling）
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wgh_tool")
public class ToolConfig extends BaseEntity {

    /**
     * 工具名称
     */
    private String name;

    /**
     * 工具类型：SKILL / MCP / HTTP / FUNCTION_CALLING
     */
    private String toolType;

    /**
     * 描述
     */
    private String description;

    /**
     * 工具定义（JSON - 根据类型不同结构不同）
     */
    private String definition;

    /**
     * 执行器配置（JSON）
     */
    private String executorConfig;

    /**
     * 状态：0-禁用，1-启用
     */
    private Integer status;

    /**
     * 是否为参考示例
     */
    @TableField(typeHandler = BooleanToSmallintTypeHandler.class)
    private Boolean isExample;
}
