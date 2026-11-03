package com.wgh.rag.publicaccess.exception;

import com.wgh.rag.common.result.Result;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 公开访问异常响应，保留 code / message，并附带前端需要的结构化字段。
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class PublicAccessExceptionHandler {

    @ExceptionHandler(PublicAccessException.class)
    public Result<Map<String, Object>> handle(PublicAccessException exception) {
        Result<Map<String, Object>> result = Result.fail(exception.getCode(), exception.getMessage());
        result.setData(exception.getData());
        return result;
    }
}
