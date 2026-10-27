package com.wgh.rag.tool.executor;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ToolExecutionResultMessage;

import java.util.List;
import java.util.Map;

/**
 * 工具执行器接口 —— 每种 toolType（HTTP / SKILL / MCP）各自实现
 */
public interface ToolExecutor {

    /**
     * 支持的 toolType
     */
    String supportedToolType();

    /**
     * 执行一个工具调用请求
     *
     * @param executorConfig 工具执行器配置（ToolConfig.executorConfig JSON 反序列化后的 Map）
     * @param request        LLM 发出的工具调用请求（含 toolName + arguments JSON）
     * @return 执行结果 ToolExecutionResultMessage（带 resultText）
     */
    ToolExecutionResultMessage execute(Map<String, Object> executorConfig, ToolExecutionRequest request);
}
