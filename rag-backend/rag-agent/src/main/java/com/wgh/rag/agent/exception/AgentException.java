package com.wgh.rag.agent.exception;

public class AgentException extends RuntimeException {
    public AgentException(String msg) { super(msg); }
    public AgentException(String msg, Throwable cause) { super(msg, cause); }

    /** Planner LLM returned invalid / unparseable JSON. */
    public static AgentException planParseFailed(String raw) {
        return new AgentException("Planner 解析失败: " + (raw.length() > 200 ? raw.substring(0, 200) + "..." : raw));
    }

    /** Step execution exceeded retries. */
    public static AgentException stepFailedAfterRetry(String stepId, int retries) {
        return new AgentException("步骤 " + stepId + " 重试 " + retries + " 次后仍然失败");
    }
}
