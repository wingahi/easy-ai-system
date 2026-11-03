package com.wgh.rag.auth.security;

/**
 * 外部 token 验签/校验失败。
 */
public class TokenVerificationException extends RuntimeException {

    public TokenVerificationException(String message) {
        super(message);
    }

    public TokenVerificationException(String message, Throwable cause) {
        super(message, cause);
    }
}
