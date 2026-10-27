package com.wgh.rag.common.result;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 统一响应状态码
 */
@Getter
@AllArgsConstructor
public enum ResultCode {

    SUCCESS(200, "操作成功"),
    FAIL(500, "操作失败"),
    
    // 鉴权相关 1xxx
    UNAUTHORIZED(1001, "未授权访问"),
    TOKEN_EXPIRED(1002, "Token已过期"),
    TOKEN_INVALID(1003, "Token无效"),
    PERMISSION_DENIED(1004, "权限不足"),
    LOGIN_FAILED(1005, "登录失败"),
    
    // 参数校验 2xxx
    PARAM_ERROR(2001, "参数错误"),
    PARAM_MISSING(2002, "参数缺失"),
    
    // 业务错误 3xxx
    RESOURCE_NOT_FOUND(3001, "资源不存在"),
    RESOURCE_ALREADY_EXISTS(3002, "资源已存在"),
    OPERATION_NOT_ALLOWED(3003, "操作不允许"),
    
    // 文件相关 4xxx
    FILE_UPLOAD_FAILED(4001, "文件上传失败"),
    FILE_NOT_SUPPORTED(4002, "不支持的文件格式"),
    FILE_PARSE_FAILED(4003, "文件解析失败"),
    
    // LLM相关 5xxx
    LLM_CALL_FAILED(5001, "大模型调用失败"),
    LLM_TIMEOUT(5002, "大模型调用超时"),
    EMBEDDING_FAILED(5003, "嵌入模型调用失败");

    private final Integer code;
    private final String message;
}
