package com.wgh.rag.common.exception;

import com.wgh.rag.common.result.Result;
import com.wgh.rag.common.result.ResultCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常处理器
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusinessException(BusinessException e) {
        log.warn("业务异常: {}", e.getMessage());
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(Result.fail(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValidationException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        log.warn("参数校验失败: {}", message);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(Result.fail(ResultCode.PARAM_ERROR.getCode(), message));
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<Result<Void>> handleBindException(BindException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(Result.fail(ResultCode.PARAM_ERROR.getCode(), message));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result<Void>> handleConstraintViolationException(ConstraintViolationException e) {
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(Result.fail(ResultCode.PARAM_ERROR.getCode(), e.getMessage()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Result<Void>> handleAccessDeniedException(AccessDeniedException e) {
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(Result.fail(ResultCode.PERMISSION_DENIED));
    }

    /**
     * 唯一键冲突统一映射为 409，避免并发/重复创建时返回 500
     */
    @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class)
    public ResponseEntity<Result<Void>> handleDuplicateKeyException(
            org.springframework.dao.DuplicateKeyException e) {
        log.warn("唯一键冲突: {}", e.getMessage());
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(Result.fail(ResultCode.RESOURCE_ALREADY_EXISTS.getCode(), "资源已存在（唯一键冲突）"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result<Void>> handleIllegalArgumentException(IllegalArgumentException e) {
        log.warn("参数非法: {}", e.getMessage());
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(Result.fail(ResultCode.PARAM_ERROR.getCode(), e.getMessage()));
    }

    /**
     * 未映射路径：不再落进兜底 500。
     * <p>
     * Spring Boot 3.2+ 对无 handler 的请求抛 {@link NoResourceFoundException}。仅对
     * {@code /api/**} 返回统一 404 业务码（3001），其余静态资源路径仍返回框架原有 404，
     * 避免影响 doc.html / webjars / swagger-ui 等映射资源。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Result<Void>> handleNoResourceFound(NoResourceFoundException e,
                                                              HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri != null && uri.startsWith("/api/")) {
            log.warn("未映射接口: {} {}", request.getMethod(), uri);
            return ResponseEntity
                    .status(HttpStatus.NOT_FOUND)
                    .body(Result.fail(ResultCode.RESOURCE_NOT_FOUND.getCode(), "接口不存在"));
        }
        log.debug("未找到静态资源: {}", uri);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleException(Exception e) {
        log.error("系统异常: ", e);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.fail(ResultCode.FAIL));
    }
}
