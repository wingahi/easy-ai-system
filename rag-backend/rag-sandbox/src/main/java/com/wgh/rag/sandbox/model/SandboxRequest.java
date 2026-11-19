package com.wgh.rag.sandbox.model;

/**
 * 一次代码执行请求。
 *
 * @param language      语言名（PYTHON / JAVASCRIPT 或别名）
 * @param code          源码文本
 * @param stdin         标准输入，可为 null
 * @param timeoutMillis 期望的墙钟超时（毫秒），为 null 时使用默认值；超过上限会被裁剪
 */
public record SandboxRequest(String language, String code, String stdin, Long timeoutMillis) {

    public static SandboxRequest of(String language, String code) {
        return new SandboxRequest(language, code, null, null);
    }
}
