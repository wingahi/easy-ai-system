package com.wgh.rag.publicaccess.exception;

import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.result.ResultCode;
import lombok.Getter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 公开访问业务异常，可携带结构化字段（剩余尝试次数、锁定剩余秒数等）。
 */
@Getter
public class PublicAccessException extends BusinessException {

    private final Map<String, Object> data;

    public PublicAccessException(ResultCode resultCode, String message) {
        this(resultCode, message, null);
    }

    public PublicAccessException(ResultCode resultCode, String message, Map<String, Object> data) {
        super(resultCode, message);
        this.data = data == null ? Map.of() : new LinkedHashMap<>(data);
    }

    public static PublicAccessException forbidden(String message) {
        return new PublicAccessException(ResultCode.PERMISSION_DENIED, message);
    }

    public static PublicAccessException forbidden(String message, Map<String, Object> data) {
        return new PublicAccessException(ResultCode.PERMISSION_DENIED, message, data);
    }

    public static PublicAccessException operationNotAllowed(String message, Map<String, Object> data) {
        return new PublicAccessException(ResultCode.OPERATION_NOT_ALLOWED, message, data);
    }
}
