package com.wgh.rag.workflow.engine.executor;

import com.wgh.rag.workflow.dto.GraphNode;
import com.wgh.rag.workflow.dto.RunContext;
import com.wgh.rag.workflow.entity.WorkflowRun;
import lombok.Getter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;

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

    /** 工作流 SSE 事件出口（可为 null：单元测试/非流式场景） */
    private final BiConsumer<String, Map<String, Object>> sink;

    public NodeExecutionContext(GraphNode node, RunContext runContext, WorkflowRun run, int nestingDepth) {
        this(node, runContext, run, nestingDepth, null);
    }

    public NodeExecutionContext(GraphNode node, RunContext runContext, WorkflowRun run, int nestingDepth,
                                BiConsumer<String, Map<String, Object>> sink) {
        this.node = node;
        this.runContext = runContext;
        this.run = run;
        this.nestingDepth = nestingDepth;
        this.sink = sink;
    }

    /** 节点配置（防御 null） */
    public Map<String, Object> config() {
        return node.getConfig() != null ? node.getConfig() : Map.of();
    }

    /** 下发节点级可观测事件；无 sink 时静默（调用方仍会打日志） */
    public void emit(String event, Map<String, Object> payload) {
        if (sink == null) return;
        Map<String, Object> data = new LinkedHashMap<>(payload);
        data.putIfAbsent("runId", run != null ? run.getId() : null);
        if (node != null) data.putIfAbsent("nodeId", node.getId());
        try {
            sink.accept(event, data);
        } catch (Exception ignored) {
            // SSE 断开不应影响工作流执行
        }
    }
}
