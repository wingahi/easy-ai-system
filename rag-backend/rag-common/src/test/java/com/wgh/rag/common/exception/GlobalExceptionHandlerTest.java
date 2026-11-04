package com.wgh.rag.common.exception;

import com.wgh.rag.common.result.Result;
import com.wgh.rag.common.result.ResultCode;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.lang.reflect.Method;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全局异常映射契约：HTTP 状态 + 业务码（尤其是未映射 /api 的 404 + 3001）。
 */
@DisplayName("全局异常映射")
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("业务异常：HTTP 200 + 自定义业务码（与前端既有协议一致）")
    void businessExceptionUsesHttp200() {
        ResponseEntity<Result<Void>> response =
                handler.handleBusinessException(new BusinessException(ResultCode.OPERATION_NOT_ALLOWED, "不允许"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(ResultCode.OPERATION_NOT_ALLOWED.getCode());
        assertThat(response.getBody().getMessage()).isEqualTo("不允许");
    }

    @Test
    @DisplayName("未映射 /api/** → HTTP 404 + 业务码 3001，并给出统一响应体")
    void unmappedApiReturns404And3001() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/quota");

        ResponseEntity<Result<Void>> response = handler.handleNoResourceFound(
                new NoResourceFoundException(HttpMethod.GET, "api/admin/quota"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(ResultCode.RESOURCE_NOT_FOUND.getCode());
        assertThat(response.getBody().getMessage()).isEqualTo("接口不存在");
    }

    @Test
    @DisplayName("非 /api 静态资源 → HTTP 404，保持框架原有空响应体（不暴露业务码）")
    void nonApiResourceKeepsFramework404() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/css/app.css");

        ResponseEntity<Result<Void>> response = handler.handleNoResourceFound(
                new NoResourceFoundException(HttpMethod.GET, "css/app.css"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNull();
    }

    @Test
    @DisplayName("参数类型不匹配（如 /api/kb/abc）→ HTTP 400 + 2001")
    void typeMismatchReturns400() throws Exception {
        Method method = SampleController.class.getDeclaredMethod("byId", Long.class);
        MethodParameter parameter = new MethodParameter(method, 0);
        MethodArgumentTypeMismatchException ex = new MethodArgumentTypeMismatchException(
                "abc", Long.class, "id", parameter, new IllegalArgumentException("bad type"));

        ResponseEntity<Result<Void>> response = handler.handleTypeMismatchException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(ResultCode.PARAM_ERROR.getCode());
        assertThat(response.getBody().getMessage()).contains("id");
    }

    @Test
    @DisplayName("@Valid 校验失败 → HTTP 200 + 2001，消息拼接所有字段错误")
    void validationFailureReturnsParamError() throws Exception {
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Object(), "req");
        binding.addError(new FieldError("req", "name", "名称不能为空"));
        binding.addError(new FieldError("req", "size", "大小必须为正"));
        Method method = SampleController.class.getDeclaredMethod("byId", Long.class);
        MethodArgumentNotValidException ex =
                new MethodArgumentNotValidException(new MethodParameter(method, 0), binding);

        ResponseEntity<Result<Void>> response = handler.handleValidationException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(ResultCode.PARAM_ERROR.getCode());
        assertThat(response.getBody().getMessage()).contains("名称不能为空", "大小必须为正");
    }

    @Test
    @DisplayName("约束校验失败 → HTTP 200 + 2001")
    void constraintViolationReturnsParamError() {
        ResponseEntity<Result<Void>> response = handler.handleConstraintViolationException(
                new ConstraintViolationException("参数非法", Set.of()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(ResultCode.PARAM_ERROR.getCode());
    }

    @Test
    @DisplayName("唯一键冲突 → HTTP 409 + 3002")
    void duplicateKeyReturns409() {
        ResponseEntity<Result<Void>> response =
                handler.handleDuplicateKeyException(new DuplicateKeyException("duplicate key"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(ResultCode.RESOURCE_ALREADY_EXISTS.getCode());
    }

    @Test
    @DisplayName("权限拒绝 → HTTP 403 + 1004")
    void accessDeniedReturns403() {
        ResponseEntity<Result<Void>> response = handler.handleAccessDeniedException(
                new org.springframework.security.access.AccessDeniedException("denied"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(ResultCode.PERMISSION_DENIED.getCode());
    }

    @Test
    @DisplayName("IllegalArgumentException → HTTP 200 + 2001（不落入兜底 500）")
    void illegalArgumentReturnsParamError() {
        ResponseEntity<Result<Void>> response =
                handler.handleIllegalArgumentException(new IllegalArgumentException("bad argument"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(ResultCode.PARAM_ERROR.getCode());
    }

    @Test
    @DisplayName("未知异常兜底 → HTTP 500 + 500，不回显异常细节")
    void unknownExceptionReturns500() {
        ResponseEntity<Result<Void>> response =
                handler.handleException(new RuntimeException("secret internals"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(ResultCode.FAIL.getCode());
        assertThat(response.getBody().getMessage()).isEqualTo(ResultCode.FAIL.getMessage());
        assertThat(response.getBody().getMessage()).doesNotContain("secret internals");
    }

    /** 仅用于构造 MethodParameter 的样例签名 */
    static class SampleController {
        @SuppressWarnings("unused")
        public Object byId(Long id) {
            return id;
        }
    }
}
