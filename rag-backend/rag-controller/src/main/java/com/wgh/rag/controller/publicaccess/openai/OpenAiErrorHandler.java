package com.wgh.rag.controller.publicaccess.openai;

import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OpenAI 兼容入口专用错误处理：把业务异常转换为 OpenAI 错误结构
 * （{@code {"error":{"message","type","param","code"}}}）并保留正确的 HTTP 状态码，
 * 避免落到全局 Result 结构（HTTP 200 + 业务码）。
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = OpenAiCompatController.class)
public class OpenAiErrorHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> handleBusinessException(BusinessException e) {
        HttpStatus status = mapStatus(e.getCode());
        return build(status, e.getMessage(), e.getCode());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadableBody(HttpMessageNotReadableException e) {
        log.warn("OpenAI 兼容接口请求体解析失败: {}", e.getMessage());
        return build(HttpStatus.BAD_REQUEST, "请求体不是合法的 JSON", ResultCode.PARAM_ERROR.getCode());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleException(Exception e) {
        log.error("OpenAI 兼容接口异常", e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "服务内部错误", ResultCode.FAIL.getCode());
    }

    private ResponseEntity<Map<String, Object>> build(HttpStatus status, String message, Integer bizCode) {
        String type;
        String code;
        if (status == HttpStatus.UNAUTHORIZED) {
            type = "authentication_error";
            code = "invalid_api_key";
        } else if (status == HttpStatus.FORBIDDEN) {
            type = "permission_error";
            code = "insufficient_permissions";
        } else if (status == HttpStatus.NOT_FOUND) {
            type = "invalid_request_error";
            code = "model_not_found";
        } else if (status == HttpStatus.TOO_MANY_REQUESTS) {
            type = "rate_limit_error";
            code = "insufficient_quota";
        } else if (status == HttpStatus.BAD_REQUEST) {
            type = "invalid_request_error";
            code = "invalid_request";
        } else if (status == HttpStatus.GATEWAY_TIMEOUT) {
            type = "api_error";
            code = "timeout";
        } else if (status == HttpStatus.BAD_GATEWAY) {
            type = "api_error";
            code = "upstream_error";
        } else {
            type = "api_error";
            code = "internal_error";
        }
        if (message == null || message.isBlank()) {
            message = "请求处理失败";
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("message", message);
        detail.put("type", type);
        detail.put("param", null);
        detail.put("code", code);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", detail);
        return ResponseEntity.status(status).body(body);
    }

    private HttpStatus mapStatus(Integer bizCode) {
        if (bizCode == null) {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
        if (bizCode.equals(ResultCode.UNAUTHORIZED.getCode())
                || bizCode.equals(ResultCode.TOKEN_EXPIRED.getCode())
                || bizCode.equals(ResultCode.TOKEN_INVALID.getCode())
                || bizCode.equals(ResultCode.SESSION_INVALID.getCode())
                || bizCode.equals(ResultCode.LOGIN_FAILED.getCode())) {
            return HttpStatus.UNAUTHORIZED;
        }
        if (bizCode.equals(ResultCode.PERMISSION_DENIED.getCode())
                || bizCode.equals(ResultCode.PUBLIC_ACCESS_LOCKED.getCode())) {
            return HttpStatus.FORBIDDEN;
        }
        if (bizCode.equals(ResultCode.QUOTA_EXCEEDED.getCode())) {
            return HttpStatus.TOO_MANY_REQUESTS;
        }
        if (bizCode.equals(ResultCode.RESOURCE_NOT_FOUND.getCode())) {
            return HttpStatus.NOT_FOUND;
        }
        if (bizCode.equals(ResultCode.PARAM_ERROR.getCode())
                || bizCode.equals(ResultCode.PARAM_MISSING.getCode())
                || bizCode.equals(ResultCode.OPERATION_NOT_ALLOWED.getCode())) {
            return HttpStatus.BAD_REQUEST;
        }
        if (bizCode.equals(ResultCode.LLM_TIMEOUT.getCode())) {
            return HttpStatus.GATEWAY_TIMEOUT;
        }
        if (bizCode.equals(ResultCode.LLM_CALL_FAILED.getCode())
                || bizCode.equals(ResultCode.EMBEDDING_FAILED.getCode())) {
            return HttpStatus.BAD_GATEWAY;
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }
}
