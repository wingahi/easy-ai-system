package com.wgh.rag.workflow.constant;

/**
 * 工作流模块 SSE 事件名常量 —— 与前端工作流编辑器/运行面板的事件处理逻辑一一对应。
 * 字符串值为对外协议，禁止改动。
 */
public final class WorkflowEvents {

    private WorkflowEvents() {
    }

    /** 工作流开始运行 */
    public static final String WORKFLOW_START = "workflow_start";
    /** 工作流运行失败 */
    public static final String WORKFLOW_FAILED = "workflow_failed";
    /** 工作流被取消 */
    public static final String WORKFLOW_CANCELLED = "workflow_cancelled";
    /** 工作流运行完成 */
    public static final String WORKFLOW_COMPLETE = "workflow_complete";
    /** 节点开始执行 */
    public static final String NODE_START = "node_start";
    /** 节点产出结果 */
    public static final String NODE_OUTPUT = "node_output";
    /** 节点执行出错 */
    public static final String NODE_ERROR = "node_error";
    /** 节点等待人工确认 */
    public static final String NODE_HITL_PENDING = "node_hitl_pending";
}
