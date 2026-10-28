package com.wgh.rag.workflow.engine.executor;

import com.wgh.rag.workflow.dto.GraphNode;
import com.wgh.rag.workflow.dto.RunContext;
import com.wgh.rag.workflow.entity.WorkflowRun;
import lombok.Getter;

import java.util.Map;

/**
 * 节点执行上下文 —— 承载 executeNode 各分支实际需要的入参。
 */
@Getter
public class NodeExecutionContext {

    /** 当前执行的节点定义 */
    private final GraphNode node;

    /** 运行上下文（变量池 / 节点输出 / 循环轮次） */
    private final RunContext runContext;

    /** 当前运行实例 */
    private final WorkflowRun run;

    /** 子流程嵌套深度（顶层为 1） */
    private final int nestingDepth;

    public NodeExecutionContext(GraphNode node, RunContext runContext, WorkflowRun run, int nestingDepth) {
        this.node = node;
        this.runContext = runContext;
        this.run = run;
        this.nestingDepth = nestingDepth;
    }

    /** 节点配置（防御 null） */
    public Map<String, Object> config() {
        return node.getConfig() != null ? node.getConfig() : Map.of();
    }
}
