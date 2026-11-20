package com.wgh.rag.execution.job;

import com.wgh.rag.execution.event.ExecutionEventSink;
import com.wgh.rag.execution.model.ExecutionRequest;
import com.wgh.rag.execution.model.JobPayload;

/**
 * 单次执行的运行上下文。
 *
 * <p>由调度层构造并注入 {@link JobHandler}。执行体只应通过本接口获取上下文、发送事件、
 * 检查取消与续租，不得回读 HTTP 请求上下文（登录态、MDC、租户 ThreadLocal）。
 */
public interface JobContext {

    /**
     * 执行标识。
     *
     * @return 执行标识
     */
    String executionId();

    /**
     * 提交时的原始请求。
     *
     * @return 执行请求
     */
    ExecutionRequest request();

    /**
     * 作业负载，等价于 {@code request().payload()}。
     *
     * @return 负载
     */
    default JobPayload payload() {
        return request().payload();
    }

    /**
     * 事件出口。
     *
     * @return 事件出口
     */
    ExecutionEventSink events();

    /**
     * 取消令牌。
     *
     * @return 取消令牌
     */
    ExecutionCancellationToken cancellation();

    /**
     * 续租心跳。
     *
     * <p>后台/维护作业在长循环中周期性调用；租约已丢失时抛出
     * {@link com.wgh.rag.execution.error.LeaseLostException}，执行体应立即停止写入。
     * 交互式执行不持有租约，调用应为无操作。
     */
    void heartbeat();
}
