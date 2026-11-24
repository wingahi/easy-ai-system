package com.wgh.rag.execution.error;

/**
 * 执行错误码。
 *
 * <p>与统一调度方案 §3.8 对齐；管理端与前端按错误码分支处理，不解析错误文案。
 */
public final class ExecutionErrorCodes {

    /** 调度层拒绝：车道池满且排队超限。 */
    public static final String SCHEDULER_REJECTED = "SCHEDULER_REJECTED";

    /** 租户并发上限已满。 */
    public static final String TENANT_CONCURRENCY_EXCEEDED = "TENANT_CONCURRENCY_EXCEEDED";

    /** 后台作业排队超时。 */
    public static final String JOB_QUEUE_TIMEOUT = "JOB_QUEUE_TIMEOUT";

    /** 租约丢失：续租失败或已被其它实例接管。 */
    public static final String LEASE_LOST = "LEASE_LOST";

    /** 所属实例失联：租约未到期也被其它实例提前回收。 */
    public static final String INSTANCE_LOST = "INSTANCE_LOST";

    /** 负载结构不兼容：旧版本 payload 无法被当前代码执行。 */
    public static final String INCOMPATIBLE_PAYLOAD = "INCOMPATIBLE_PAYLOAD";

    /** 作业类型未注册对应处理器。 */
    public static final String JOB_TYPE_UNKNOWN = "JOB_TYPE_UNKNOWN";

    /** 执行被取消。 */
    public static final String CANCELLED = "CANCELLED";

    /** 执行超时。 */
    public static final String EXECUTION_TIMEOUT = "EXECUTION_TIMEOUT";

    private ExecutionErrorCodes() {
    }
}
