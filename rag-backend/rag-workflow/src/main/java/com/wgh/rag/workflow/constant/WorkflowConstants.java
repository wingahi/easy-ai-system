package com.wgh.rag.workflow.constant;

/**
 * 工作流常量：节点类型、运行/步骤状态、onError 策略（与 wgh_workflow_run / wgh_workflow_node_run 持久化值一致）。
 */
public interface WorkflowConstants {

    // ============ 节点类型 ============
    String NODE_START = "START";
    String NODE_END = "END";
    String NODE_LLM = "LLM";
    String NODE_RETRIEVAL = "RETRIEVAL";
    String NODE_TOOL = "TOOL";
    String NODE_CONDITION = "CONDITION";
    String NODE_LOOP = "LOOP";
    String NODE_HITL = "HITL";
    String NODE_VARIABLE = "VARIABLE";
    String NODE_WORKFLOW = "WORKFLOW";
    String NODE_AGENT = "AGENT";

    // ============ 运行 / 节点状态 ============
    String STATUS_PENDING = "PENDING";
    String STATUS_RUNNING = "RUNNING";
    String STATUS_PAUSED = "PAUSED";
    String STATUS_COMPLETED = "COMPLETED";
    String STATUS_FAILED = "FAILED";
    String STATUS_CANCELLED = "CANCELLED";
    String STATUS_SUCCESS = "SUCCESS";
    String STATUS_HITL_PENDING = "HITL_PENDING";

    // ============ onError 策略 ============
    String ON_ERROR_STOP = "STOP";
}
