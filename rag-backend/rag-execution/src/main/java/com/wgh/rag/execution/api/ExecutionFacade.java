package com.wgh.rag.execution.api;

import com.wgh.rag.execution.event.ExecutionEventSink;
import com.wgh.rag.execution.model.ExecutionHandle;
import com.wgh.rag.execution.model.ExecutionRequest;
import com.wgh.rag.execution.model.ExecutionSnapshot;
import java.util.Optional;

/**
 * 执行门面：执行域的唯一起点。
 *
 * <p>交互式与后台作业共用同一套提交/查询/取消语义：
 * <ul>
 *   <li>{@link #submit(ExecutionRequest)} 只做准入与登记，不阻塞到执行完成；</li>
 *   <li>{@link #query(String)} 读取统一状态快照；</li>
 *   <li>{@link #cancel(String, String)} 置取消标记，由执行体协作退出；</li>
 *   <li>{@link #attach(String, long, ExecutionEventSink)} 订阅事件，供 SSE 接入层使用。</li>
 * </ul>
 *
 * <p>实现位于调度层；执行域只依赖本接口。
 */
public interface ExecutionFacade {

    /**
     * 提交执行。
     *
     * <p>调用方在提交前应完成配额检查（准入 → 配额 → 执行），避免被拒绝后误扣额度。
     *
     * @param request 执行请求
     * @return 执行句柄
     * @throws com.wgh.rag.execution.error.ExecutionException 准入拒绝、租户并发超限、排队超时等
     */
    ExecutionHandle submit(ExecutionRequest request);

    /**
     * 查询执行快照。
     *
     * @param executionId 执行标识
     * @return 快照；不存在时为空
     */
    Optional<ExecutionSnapshot> query(String executionId);

    /**
     * 请求取消。
     *
     * @param executionId 执行标识
     * @param reason      取消原因
     * @return {@code true} 表示已受理（执行体可能仍需时间退出）
     */
    boolean cancel(String executionId, String reason);

    /**
     * 订阅执行事件。
     *
     * @param executionId 执行标识
     * @param fromSeq     从该序号之后开始推送；传 0 表示只推送新事件
     * @param sink        事件出口
     * @return 用于取消订阅的句柄
     */
    AutoCloseable attach(String executionId, long fromSeq, ExecutionEventSink sink);
}
