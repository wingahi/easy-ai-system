package com.wgh.rag.tool.fallback;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具失败分类器测试：错误分类边界。
 */
@DisplayName("工具失败分类")
class ToolFailureClassifierTest {

    @Test
    @DisplayName("HTTP 状态码边界")
    void classifyHttpStatus() {
        assertThat(ToolFailureClassifier.classify("HTTP 401 - unauthorized")).isEqualTo(ToolFailureKind.AUTH);
        assertThat(ToolFailureClassifier.classify("HTTP 403 - forbidden")).isEqualTo(ToolFailureKind.AUTH);
        assertThat(ToolFailureClassifier.classify("HTTP 400 - bad")).isEqualTo(ToolFailureKind.BAD_PARAM);
        assertThat(ToolFailureClassifier.classify("HTTP 422 - invalid")).isEqualTo(ToolFailureKind.BAD_PARAM);
        assertThat(ToolFailureClassifier.classify("HTTP 404 - missing")).isEqualTo(ToolFailureKind.NOT_FOUND);
        assertThat(ToolFailureClassifier.classify("HTTP 410 - gone")).isEqualTo(ToolFailureKind.NOT_FOUND);
        assertThat(ToolFailureClassifier.classify("HTTP 429 - slow down")).isEqualTo(ToolFailureKind.RATE_LIMIT);
        assertThat(ToolFailureClassifier.classify("HTTP 500 - boom")).isEqualTo(ToolFailureKind.SERVER);
        assertThat(ToolFailureClassifier.classify("HTTP 503 - unavailable")).isEqualTo(ToolFailureKind.SERVER);
        assertThat(ToolFailureClassifier.classify("HTTP 418 - teapot")).isEqualTo(ToolFailureKind.UNKNOWN);
        assertThat(ToolFailureClassifier.classify("HTTP - 无状态码")).isEqualTo(ToolFailureKind.UNKNOWN);
    }

    @Test
    @DisplayName("异常类名映射")
    void classifyException() {
        assertThat(ToolFailureClassifier.classify("Error: HttpTimeoutException - timeout"))
                .isEqualTo(ToolFailureKind.NETWORK);
        assertThat(ToolFailureClassifier.classify("Error: ConnectException - refused"))
                .isEqualTo(ToolFailureKind.NETWORK);
        assertThat(ToolFailureClassifier.classify("Error: UnknownHostException - dns"))
                .isEqualTo(ToolFailureKind.NETWORK);
        assertThat(ToolFailureClassifier.classify("Error: SsrfBlockedException - blocked"))
                .isEqualTo(ToolFailureKind.SSRF);
        assertThat(ToolFailureClassifier.classify("Error: com.wgh.rag.common.net.SsrfBlockedException - blocked"))
                .isEqualTo(ToolFailureKind.SSRF);
        assertThat(ToolFailureClassifier.classify("Error: IllegalArgumentException - bad arg"))
                .isEqualTo(ToolFailureKind.BAD_PARAM);
        assertThat(ToolFailureClassifier.classify("Error: RateLimitExceededException - slow"))
                .isEqualTo(ToolFailureKind.RATE_LIMIT);
        assertThat(ToolFailureClassifier.classify("Error: WeirdException - ?"))
                .isEqualTo(ToolFailureKind.UNKNOWN);
    }

    @Test
    @DisplayName("成功与空文本")
    void classifySuccess() {
        assertThat(ToolFailureClassifier.classify("{\"ok\":true}")).isNull();
        assertThat(ToolFailureClassifier.classify("操作成功")).isNull();
        assertThat(ToolFailureClassifier.isSuccess("[]")).isTrue();
        assertThat(ToolFailureClassifier.classify("")).isEqualTo(ToolFailureKind.UNKNOWN);
        assertThat(ToolFailureClassifier.classify(null)).isEqualTo(ToolFailureKind.UNKNOWN);
    }

    @Test
    @DisplayName("可重试判定：仅网络/限流/5xx")
    void retryableKinds() {
        assertThat(ToolFailureKind.NETWORK.retryable()).isTrue();
        assertThat(ToolFailureKind.RATE_LIMIT.retryable()).isTrue();
        assertThat(ToolFailureKind.SERVER.retryable()).isTrue();
        assertThat(ToolFailureKind.AUTH.retryable()).isFalse();
        assertThat(ToolFailureKind.BAD_PARAM.retryable()).isFalse();
        assertThat(ToolFailureKind.NOT_FOUND.retryable()).isFalse();
        assertThat(ToolFailureKind.SSRF.retryable()).isFalse();
        assertThat(ToolFailureKind.UNKNOWN.retryable()).isFalse();
    }
}
