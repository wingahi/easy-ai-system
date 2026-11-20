package com.wgh.rag.execution.job;

/**
 * 作业处理器。
 *
 * <p>执行域模块为每类执行体实现一个处理器并注册为 Spring Bean；调度层只按
 * {@link #jobType()} 查找，不依赖任何具体执行域实现（依赖倒置）。
 *
 * <p>实现约定：
 * <ul>
 *   <li>无状态，可被多个线程并发调用；</li>
 *   <li>幂等：同一 {@code executionId} 可能因租约过期被重放；</li>
 *   <li>不吞掉取消：捕获 {@link com.wgh.rag.execution.error.ExecutionCancelledException} 后应继续抛出；</li>
 *   <li>不自行落终态：终态由调度层根据返回值/异常统一写入。</li>
 * </ul>
 */
public interface JobHandler {

    /**
     * 作业类型，与 {@code ExecutionRequest.jobType()} 对应。
     *
     * @return 作业类型
     */
    String jobType();

    /**
     * 执行作业。
     *
     * @param context 运行上下文
     * @throws Exception 执行失败；调度层按策略决定重试或置 {@code FAILED/DEAD}
     */
    void execute(JobContext context) throws Exception;

    /**
     * 取消钩子：调度层已置取消标记后调用，用于释放外部资源（模型流、MCP 会话、临时文件）。
     *
     * <p>默认无操作；实现应尽量幂等且快速返回。
     *
     * @param context 运行上下文
     */
    default void cancel(JobContext context) {
    }
}
