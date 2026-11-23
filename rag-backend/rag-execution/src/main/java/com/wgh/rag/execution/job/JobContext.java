package com.wgh.rag.execution.job;

import com.wgh.rag.execution.event.ExecutionEventSink;
import com.wgh.rag.execution.model.ExecutionRequest;
import com.wgh.rag.execution.model.JobPayload;

import java.util.Map;

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

    /**
     * 已持久化的执行进度（来自 {@code wgh_execution_job.result_json}）。
     *
     * <p>用于中断重试时定位原业务 run，例如 {@code WF_RUN} 在首次执行后写入
     * {@code runId}，租约回收后的重试据此续跑而不是新建 run。
     *
     * @return 进度快照，未写入时为空 Map
     */
    default Map<String, Object> savedResult() {
        return Map.of();
    }

    /**
     * 持久化执行进度。
     *
     * <p>只在当前实例仍持有租约时写入；写入失败（租约丢失）抛出
     * {@link com.wgh.rag.execution.error.LeaseLostException}。
     *
     * @param result 进度，需可 JSON 序列化
     */
    default void saveResult(Map<String, Object> result) {
        // 兼容仅实现 execute 的测试/轻量 handler
    }
}
