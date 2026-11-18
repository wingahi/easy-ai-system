package com.wgh.rag.workflow.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 运行上下文 —— 持久化到 wgh_workflow_run.context_json。
 */
@Data
public class RunContext {

    /** 变量池：input / lastOutput / 用户赋值变量 */
    private Map<String, Object> vars = new HashMap<>();

    /** 节点输出池：nodeId → { text / sources / result / iteration ... } */
    private Map<String, Map<String, Object>> nodeOutputs = new HashMap<>();

    /** 循环节点已执行轮次：nodeId → iteration */
    private Map<String, Integer> loopIterations = new HashMap<>();

    /** HITL 暂停后待恢复的节点（后继节点 ID 列表） */
    private List<String> pendingNodeIds = new ArrayList<>();

    /** 当前循环轮次（变量 {{loop.iteration}} 使用，不参与持久化语义） */
    private Integer currentLoopIteration = 0;

    /**
     * 安全模式（分享态运行）：为 true 时禁止执行有写操作副作用的工具节点
     * （HTTP 非 GET/HEAD、MCP）。随上下文持久化，HITL 恢复后仍然生效。
     */
    private Boolean safeMode = false;

    // ===== 单步调试游标（仅调试运行使用，普通运行保持 null/空，不影响原执行链路） =====

    /** 调试会话待执行的节点队列 */
    private List<String> debugFrontier;

    /** 调试会话各节点剩余入边数 */
    private Map<String, Integer> debugPendingIn;

    /** 调试会话累计已执行节点数（用于总执行次数上限保护） */
    private Integer debugExecutedCount = 0;
}
