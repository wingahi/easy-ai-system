package com.wgh.rag.common;

import com.wgh.rag.common.result.Result;
import com.wgh.rag.common.result.ResultCode;
import com.wgh.rag.common.result.PageResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 统一响应 Result / PageResult 单元测试
 */
@DisplayName("统一响应测试")
class ResultTest {

    @Test
    @DisplayName("success() 无参 - 默认 code=200")
    void success_noArg_defaultCode() {
        Result<Void> result = Result.success();
        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getMessage()).isEqualTo("操作成功");
        assertThat(result.getData()).isNull();
        assertThat(result.getTimestamp()).isPositive();
    }

    @Test
    @DisplayName("success(data) - 数据正确返回")
    void success_withData() {
        String data = "hello";
        Result<String> result = Result.success(data);
        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData()).isEqualTo(data);
    }

    @Test
    @DisplayName("fail(ResultCode) - 业务错误码")
    void fail_withResultCode() {
        Result<Void> result = Result.fail(ResultCode.RESOURCE_NOT_FOUND);
        assertThat(result.getCode()).isEqualTo(3001);
        assertThat(result.getMessage()).isEqualTo("资源不存在");
    }

    @Test
    @DisplayName("fail(code, message) - 自定义错误")
    void fail_withCodeAndMessage() {
        Result<Void> result = Result.fail(9999, "自定义错误");
        assertThat(result.getCode()).isEqualTo(9999);
        assertThat(result.getMessage()).isEqualTo("自定义错误");
    }

    @Test
    @DisplayName("fail(message) - 纯消息")
    void fail_withMessageOnly() {
        Result<Void> result = Result.fail("出错了");
        assertThat(result.getCode()).isEqualTo(500);
        assertThat(result.getMessage()).isEqualTo("出错了");
    }

    @Test
    @DisplayName("ResultCode 枚举完整性")
    void resultCode_enumValues() {
        // 校验几个关键枚举
        assertThat(ResultCode.SUCCESS.getCode()).isEqualTo(200);
        assertThat(ResultCode.FAIL.getCode()).isEqualTo(500);
        assertThat(ResultCode.UNAUTHORIZED.getCode()).isEqualTo(1001);
        assertThat(ResultCode.TOKEN_EXPIRED.getCode()).isEqualTo(1002);
        assertThat(ResultCode.PARAM_ERROR.getCode()).isEqualTo(2001);
        assertThat(ResultCode.FILE_UPLOAD_FAILED.getCode()).isEqualTo(4001);
        assertThat(ResultCode.LLM_CALL_FAILED.getCode()).isEqualTo(5001);
    }

    @Test
    @DisplayName("PageResult 分页结果")
    void pageResult_build() {
        List<String> records = List.of("a", "b", "c");
        PageResult<String> pageResult = new PageResult<>(3L, 1L, 10L, records);
        
        assertThat(pageResult.getTotal()).isEqualTo(3L);
        assertThat(pageResult.getPage()).isEqualTo(1L);
        assertThat(pageResult.getSize()).isEqualTo(10L);
        assertThat(pageResult.getRecords()).hasSize(3);
    }
}
