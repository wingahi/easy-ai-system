package com.wgh.rag.tool.fallback;

/**
 * 工具失败分类。
 *
 * <p>仅 {@link #retryable()} 为 true 的类型允许重试；SSRF / 鉴权 / 参数错误一类
 * 重试既无意义，还会放大出站探测与鉴权爆破风险，因此一律不重试。
 */
public enum ToolFailureKind {

    /** 网络不可达、超时、连接被重置 */
    NETWORK(true),
    /** 限流（HTTP 429） */
    RATE_LIMIT(true),
    /** 服务端错误（HTTP 5xx） */
    SERVER(true),
    /** 鉴权失败（HTTP 401/403） */
    AUTH(false),
    /** 参数错误（HTTP 400/422） */
    BAD_PARAM(false),
    /** 资源不存在（HTTP 404/410） */
    NOT_FOUND(false),
    /** SSRF 防护拦截：绝不重试，避免放大出站探测 */
    SSRF(false),
    /** 无法归类的错误 */
    UNKNOWN(false),
    /** 熔断打开，本次调用被短路（非工具本身返回） */
    CIRCUIT_OPEN(false),
    /** 任务级请求数 / 总时长预算耗尽（非工具本身返回） */
    BUDGET_EXHAUSTED(false);

    private final boolean retryable;

    ToolFailureKind(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}
