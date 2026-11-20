package com.wgh.rag.execution.run;

import com.wgh.rag.execution.job.JobContext;
import com.wgh.rag.execution.model.ExecutionSnapshot;
import java.util.Optional;

/**
 * 业务运行记录适配器。
 *
 * <p>各执行域已有的运行记录（工作流 run、Agent plan、多 Agent run 等）通过实现本接口
 * 接入统一查询与续跑，替代原先在接入层按类型 {@code switch} 的做法。
 *
 * <p>每个执行域模块实现一个 adapter 并注册为 Spring Bean；调度层只按
 * {@link #runType()} 查找。
 */
public interface RunAdapter {

    /**
     * 运行类型（如 {@code workflow}、{@code agent-plan}、{@code multi-agent}）。
     *
     * @return 运行类型
     */
    String runType();

    /**
     * 读取运行快照。
     *
     * @param runId 业务运行记录标识
     * @return 快照；不存在时为空
     */
    Optional<ExecutionSnapshot> load(String runId);

    /**
     * 从断点继续运行。
     *
     * <p>由调度层的协调器在恢复流程中调用；实现必须幂等，且不得在请求线程内长阻塞。
     *
     * @param runId   业务运行记录标识
     * @param context 运行上下文（事件出口、取消令牌、心跳）
     */
    void resume(String runId, JobContext context);

    /**
     * 取消运行。
     *
     * @param runId 业务运行记录标识
     * @return {@code true} 表示已受理
     */
    boolean cancel(String runId);
}
