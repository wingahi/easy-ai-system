package com.wgh.rag.llm.failover;

/**
 * 故障转移链路的统一错误文案与脱敏工具。
 */
public final class FailoverErrors {

    private FailoverErrors() {
    }

    /** 日志用错误描述：只保留异常类型与脱敏后的消息，避免把 API Key 写进日志 */
    public static String describe(Throwable error) {
        if (error == null) return "未知错误";
        String msg = error.getMessage();
        return error.getClass().getSimpleName() + (msg != null && !msg.isBlank() ? ": " + redact(msg) : "");
    }

    /** 前端可见的错误消息：同样做脱敏 */
    public static String userFacing(Throwable error) {
        if (error == null) return "未知错误";
        String msg = error.getMessage();
        return msg != null && !msg.isBlank() ? redact(msg) : error.getClass().getSimpleName();
    }

    public static String redact(String text) {
        return text.replaceAll("(?i)(sk-[A-Za-z0-9_-]{6,}|api[_-]?key[=: ]+[^ ,;]+|bearer +[A-Za-z0-9._-]+)", "***");
    }

    /** 全部候选模型均失败时的明确业务错误文案 */
    public static String allModelsFailed(int count, Throwable lastError) {
        return "所有候选模型均调用失败（共 " + count + " 个，最后错误: "
                + userFacing(lastError) + "）";
    }
}
