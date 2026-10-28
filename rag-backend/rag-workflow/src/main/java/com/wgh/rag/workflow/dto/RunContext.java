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
}
