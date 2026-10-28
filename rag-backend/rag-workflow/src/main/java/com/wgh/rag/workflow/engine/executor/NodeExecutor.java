package com.wgh.rag.workflow.engine.executor;

import com.wgh.rag.workflow.dto.NodeResult;

/**
 * 节点执行器策略接口 —— 每种节点类型一个实现，由 NodeExecutorRegistry 汇总路由。
 */
public interface NodeExecutor {

    /** 支持的节点类型："START" / "LLM" / "CONDITION" / ... */
    String supportedType();

    /** 执行节点语义，返回节点输出（调度、事件、持久化由引擎负责） */
    NodeResult execute(NodeExecutionContext context) throws Exception;
}
