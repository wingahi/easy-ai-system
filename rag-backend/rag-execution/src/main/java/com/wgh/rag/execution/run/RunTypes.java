package com.wgh.rag.execution.run;

/**
 * 可续跑的执行域类型。
 *
 * <p>与 {@link RunAdapter#runType()} 的返回值一一对应，也是 {@code RUN_RESUME} 负载中
 * {@code runType} 的合法取值。
 */
public final class RunTypes {

    public static final String WORKFLOW = "WORKFLOW";
    public static final String AGENT = "AGENT";
    public static final String MULTI_AGENT = "MULTI_AGENT";

    private RunTypes() {
    }
}
