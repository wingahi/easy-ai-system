package com.wgh.rag.chat.constant;

/**
 * 聊天模块 SSE 事件名常量 —— 与前端 ChatPlayground / ShareChat 的事件处理逻辑一一对应。
 * 字符串值为对外协议，禁止改动。
 */
public final class ChatEvents {

    private ChatEvents() {
    }

    /** 会话开始 */
    public static final String START = "start";
    /** 检索完成，下发召回信息 */
    public static final String RETRIEVE = "retrieve";
    /** 增量文本 */
    public static final String CONTENT = "content";
    /** 引用来源 */
    public static final String SOURCES = "sources";
    /** 回答完成 */
    public static final String DONE = "done";
    /** 正确率指标 */
    public static final String ACCURACY = "accuracy";
    /** 全链路指标 */
    public static final String METRICS = "metrics";
    /** 模型故障转移切换 */
    public static final String MODEL_SWITCH = "model_switch";
    /** 工具调用开始 */
    public static final String TOOL_CALL_START = "tool_call_start";
    /** 工具调用结果 */
    public static final String TOOL_CALL_RESULT = "tool_call_result";
    /** LLM-as-judge 评估结果 */
    public static final String JUDGE = "judge";
    /** 错误 */
    public static final String ERROR = "error";
}
