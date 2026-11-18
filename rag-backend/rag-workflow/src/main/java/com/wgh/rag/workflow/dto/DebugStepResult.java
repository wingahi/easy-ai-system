package com.wgh.rag.workflow.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单步调试结果 —— 一次调试请求推进若干节点后的快照（不包含 SSE 逻辑）。
 *
 * <p>status 取值：{@code DEBUG_PAUSED}（还有后续节点，可继续单步）、
 * {@code COMPLETED}（流程已正常结束）、{@code FAILED}（节点执行失败）。
 */
@Data
public class DebugStepResult {

    private Long runId;

    private Long workflowId;

    /** DEBUG_PAUSED / COMPLETED / FAILED */
    private String status;

    /** 本次调用执行的节点数 */
    private int stepCount;

    /** 是否已结束（status != DEBUG_PAUSED） */
    private boolean finished;

    /** 最终输出（仅 COMPLETED 时有值） */
    private String output;

    /** 失败原因（仅 FAILED 时有值） */
    private String error;

    /** 本次调用中每个节点的输入/输出快照 */
    private List<Step> steps = new ArrayList<>();

    /** 调试上下文快照：vars / nodeOutputs */
    private Map<String, Object> context = new LinkedHashMap<>();

    @Data
    public static class Step {

        private String nodeId;

        private String nodeName;

        private String nodeType;

        private String status;

        private int iteration;

        private long durationMs;

        /** 节点输入快照：渲染后的节点配置 + 直接上游节点输出 */
        private Map<String, Object> input;

        /** 节点输出（写入上下文 nodeOutputs 的内容） */
        private Map<String, Object> output;

        private String error;
    }
}
