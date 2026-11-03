package com.wgh.rag.chat.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.wgh.rag.data.entity.BaseEntity;
import com.wgh.rag.data.handler.BooleanToSmallintTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Prompt 模板（{{var}} 占位符；聊天窗口 system_prompt 支持 template:模板名 引用）
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wgh_prompt_template")
public class PromptTemplate extends BaseEntity {

    /**
     * 模板名（唯一，template: 引用键）
     */
    private String name;

    /**
     * 分类（RAG / 客服 / 总结 等）
     */
    private String category;

    /**
     * 模板内容（{{var}} 占位符）
     */
    private String content;

    /**
     * 变量名 JSON 数组（保存时从 content 自动提取）
     */
    private String variables;

    /**
     * 版本号（内容变更自动 +1）
     */
    private Integer version;

    /**
     * 是否内置模板（内置不可删除/改名）
     */
    @TableField(typeHandler = BooleanToSmallintTypeHandler.class)
    private Boolean builtin;

    /**
     * 备注
     */
    private String remark;
}
