package com.wgh.rag.common.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 审计日志（写操作留痕）
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wgh_audit_log")
public class AuditLog extends BaseEntity {

    /**
     * 操作人用户名（未登录为 anonymous）
     */
    private String username;

    /**
     * 操作标识（如 KB_CREATE / MODEL_DELETE）
     */
    private String action;

    /**
     * 资源类型（如 KNOWLEDGE_BASE / LLM_MODEL）
     */
    private String resourceType;

    /**
     * 资源 ID（尽力提取）
     */
    private String resourceId;

    /**
     * 操作详情（方法 + 参数摘要，截断 500 字）
     */
    private String detail;

    /**
     * 客户端 IP
     */
    private String ip;

    /**
     * 结果：SUCCESS / FAIL
     */
    private String status;
}
