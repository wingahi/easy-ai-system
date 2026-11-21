package com.wgh.rag.scheduler.admission;

/**
 * 准入结论。
 *
 * @param allowed 是否允许提交
 * @param code    拒绝时的 {@link com.wgh.rag.execution.error.ExecutionErrorCodes} 错误码
 * @param message 拒绝原因
 */
public record AdmissionDecision(boolean allowed, String code, String message) {

    /** 允许提交。 */
    public static AdmissionDecision permit() {
        return new AdmissionDecision(true, null, null);
    }

    /** 拒绝提交。 */
    public static AdmissionDecision reject(String code, String message) {
        return new AdmissionDecision(false, code, message);
    }
}
