package com.wgh.rag.agent.constant;

/**
 * Agent 常量：plan / step 状态值（与 wgh_agent_plan / wgh_agent_step 持久化值一致）。
 */
public interface AgentConstants {

    String STATUS_PENDING = "PENDING";
    String STATUS_RUNNING = "RUNNING";
    String STATUS_PAUSED = "PAUSED";
    String STATUS_COMPLETED = "COMPLETED";
    String STATUS_FAILED = "FAILED";
    String STATUS_CANCELLED = "CANCELLED";
    String STATUS_SKIPPED = "SKIPPED";
    String STATUS_SUCCESS = "SUCCESS";
    String STATUS_HITL_PENDING = "HITL_PENDING";
}
