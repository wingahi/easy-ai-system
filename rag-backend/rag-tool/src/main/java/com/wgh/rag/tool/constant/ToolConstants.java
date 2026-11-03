package com.wgh.rag.tool.constant;

/**
 * 工具模块常量：工具类型、默认超时与执行上限（与 wgh_tool.tool_type 持久化值一致）。
 */
public interface ToolConstants {

    String TYPE_HTTP = "HTTP";
    String TYPE_MCP = "MCP";
    String TYPE_SKILL = "SKILL";
    String TYPE_FUNCTION_CALLING = "FUNCTION_CALLING";

    /** HTTP 工具建连超时（秒） */
    int HTTP_CONNECT_TIMEOUT_SECONDS = 5;
    /** HTTP 工具默认请求超时（毫秒） */
    int DEFAULT_HTTP_TIMEOUT_MS = 10000;
    /** 工具结果最大保留字符数，超出截断避免撑爆上下文 */
    int MAX_RESULT_CHARS = 4000;
    /** 单次聊天最多工具调用轮次，防止死循环 */
    int MAX_TOOL_ROUNDS = 5;
}
