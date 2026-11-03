package com.wgh.rag.auth.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.wgh.rag.data.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 权限实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wgh_permission")
public class WghPermission extends BaseEntity {

    /**
     * 权限编码（如 KB:READ / MODEL:WRITE）
     */
    private String permCode;

    /**
     * 权限名称
     */
    private String permName;

    /**
     * 资源类型（如 KNOWLEDGE_BASE / LLM_MODEL）
     */
    private String resourceType;
}
