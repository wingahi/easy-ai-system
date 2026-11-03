package com.wgh.rag.log.constant;

/**
 * 审计日志常量：状态值与资源类型（与 wgh_audit_log 持久化值一致）。
 */
public interface AuditConstants {

    String STATUS_SUCCESS = "SUCCESS";
    String STATUS_FAIL = "FAIL";

    String RESOURCE_TYPE_ACCOUNT = "ACCOUNT";
}
