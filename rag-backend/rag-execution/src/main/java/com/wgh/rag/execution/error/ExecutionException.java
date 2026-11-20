package com.wgh.rag.execution.error;

/**
 * 执行契约异常。
 *
 * <p>提交、查询、取消、续租过程中的可预期失败统一使用本异常，携带
 * {@link ExecutionErrorCodes} 中的错误码，便于接入层映射为 HTTP 状态与错误响应。
 */
public class ExecutionException extends RuntimeException {

    private final String code;

    private final String executionId;

    public ExecutionException(String code, String message) {
        this(code, message, null, null);
    }

    public ExecutionException(String code, String message, String executionId) {
        this(code, message, executionId, null);
    }

    public ExecutionException(String code, String message, String executionId, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.executionId = executionId;
    }

    /**
     * 错误码。
     *
     * @return {@link ExecutionErrorCodes} 中的常量
     */
    public String code() {
        return code;
    }

    /**
     * 关联的执行标识，可为 {@code null}。
     *
     * @return 执行标识
     */
    public String executionId() {
        return executionId;
    }
}
