package com.wgh.rag.agentmulti.constant;

/**
 * 多 Agent 常量：运行 / 步骤状态值（与 wgh_m_agent_run / wgh_m_agent_step 持久化值一致）。
 */
public interface MultiAgentConstants {

    String STATUS_PENDING = "PENDING";
    String STATUS_RUNNING = "RUNNING";
    String STATUS_PAUSED = "PAUSED";
    String STATUS_COMPLETED = "COMPLETED";
    String STATUS_FAILED = "FAILED";
    String STATUS_CANCELLED = "CANCELLED";
    String STATUS_SKIPPED = "SKIPPED";
    String STATUS_HITL_PENDING = "HITL_PENDING";
}
