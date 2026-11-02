package com.wgh.rag.kb.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.wgh.rag.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 知识库 / 文档共用标签
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wgh_tag")
public class Tag extends BaseEntity {

    /**
     * 标签名（唯一）
     */
    private String name;

    /**
     * 颜色 Hex（如 #409EFF）
     */
    private String color;

    /**
     * 备注
     */
    private String remark;
}
