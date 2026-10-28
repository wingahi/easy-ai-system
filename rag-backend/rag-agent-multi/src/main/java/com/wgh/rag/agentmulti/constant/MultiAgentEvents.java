package com.wgh.rag.agentmulti.constant;

/**
 * 多 Agent 模块 SSE 事件名常量 —— 与前端多 Agent 面板的事件处理逻辑一一对应。
 * 字符串值为对外协议，禁止改动。
 */
public final class MultiAgentEvents {

    private MultiAgentEvents() {
    }

    /** 单个 Agent 实例开始执行 */
    public static final String AGENT_START = "agent_start";
    /** 单个 Agent 实例完成 RAG 检索 */
    public static final String AGENT_RAG = "agent_rag";
    /** 单个 Agent 实例发起工具调用 */
    public static final String AGENT_TOOL_CALL_START = "agent_tool_call_start";
    /** 单个 Agent 实例工具调用返回 */
    public static final String AGENT_TOOL_CALL_RESULT = "agent_tool_call_result";
    /** 单个 Agent 实例反思评估结果 */
    public static final String AGENT_REFLECTION = "agent_reflection";
    /** 单个 Agent 实例执行结束 */
    public static final String AGENT_DONE = "agent_done";
    /** 多 Agent 运行开始 */
    public static final String MULTI_AGENT_START = "multi_agent_start";
    /** 多 Agent 运行从 HITL 断点恢复 */
    public static final String MULTI_AGENT_RESUME = "multi_agent_resume";
    /** 多 Agent 运行出错 */
    public static final String MULTI_AGENT_ERROR = "multi_agent_error";
    /** 多 Agent 运行完成 */
    public static final String MULTI_AGENT_COMPLETE = "multi_agent_complete";
    /** 多 Agent 运行等待人工确认 */
    public static final String AGENT_HITL_PENDING = "agent_hitl_pending";
    /** GroupChat 某轮发言开始 */
    public static final String AGENT_TURN_START = "agent_turn_start";
    /** GroupChat 某轮发言结束 */
    public static final String AGENT_TURN_END = "agent_turn_end";
    /** GroupChat 消息总线广播的 Agent 消息 */
    public static final String AGENT_MESSAGE = "agent_message";
}
