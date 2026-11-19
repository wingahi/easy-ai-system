package com.wgh.rag.sandbox.model;

/**
 * 沙箱拒绝执行或启动失败时抛出的异常。
 *
 * <p>只携带可安全展示的原因，不包含用户代码正文。
 */
public class SandboxException extends RuntimeException {

    public SandboxException(String message) {
        super(message);
    }

    public SandboxException(String message, Throwable cause) {
        super(message, cause);
    }
}
