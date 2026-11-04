package com.wgh.rag.tool.security;

/**
 * SSRF 防护校验失败异常（协议/主机/IP/重定向/解析不通过）。
 */
public class SsrfBlockedException extends RuntimeException {

    public SsrfBlockedException(String message) {
        super(message);
    }

    public SsrfBlockedException(String message, Throwable cause) {
        super(message, cause);
    }
}
