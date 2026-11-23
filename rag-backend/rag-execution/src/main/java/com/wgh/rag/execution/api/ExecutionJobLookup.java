package com.wgh.rag.execution.api;

/**
 * 作业查询扩展：供执行域判断某个业务 run 是否已有活跃作业。
 *
 * <p>实现位于调度层；执行域只依赖本接口，避免反向依赖 {@code rag-scheduler}。
 */
public interface ExecutionJobLookup {

    /**
     * 是否存在指定类型、已持久化该 runId 且仍活跃（PENDING/LEASED/RUNNING）的作业。
     *
     * @param jobType 作业类型，如 {@code WF_RUN}
     * @param runId   业务运行标识
     * @return {@code true} 表示已有作业在跑或待跑
     */
    boolean hasActiveJobForRun(String jobType, String runId);
}
