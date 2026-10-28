package com.wgh.rag.workflow.engine;

/**
 * HITL 暂停信号 —— 中断工作队列，由 WorkflowService 恢复时重新进入。
 */
public class PauseSignal extends RuntimeException {
    public PauseSignal(String message) {
        super(message, null, false, false);
    }
}
