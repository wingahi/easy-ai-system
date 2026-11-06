package com.wgh.rag.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Result of a single StepExecutor execution (structured, not SSE-coupled). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentStepResult {
    private String finalText;
    private List<ToolInvocationRecord> toolInvocations;
    private List<RagSourceRecord> ragSources;
    /** Chain-of-thought fragments collected during the step. */
    private List<String> thoughts;
    /** LLM output summary for downstream step context injection. */
    private String resultSummary;
    /** Step status outcome. */
    private String status; // SUCCESS / FAILED / HITL_PENDING
    /** Token usage for this step. */
    private int tokenCount;
    /** Latency in ms. */
    private long latencyMs;
    /** Optional error message if FAILED. */
    private String errorMessage;
    /** Optional HITL expected input description. */
    private String hitlExpectedInput;
    /** 本步骤发生的模型切换（用于向上层下发 model_switch 可观测事件） */
    private List<ModelSwitchRecord> modelSwitches;

    /** 一次模型故障转移切换记录 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ModelSwitchRecord {
        private String modelName;
        private Long modelId;
        private String reason;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToolInvocationRecord {
        private String toolName;
        private String arguments;
        private String result;
        private boolean success;
        private long latencyMs;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RagSourceRecord {
        private String fileName;
        private Integer chunkIndex;
        private Double score;
        private String content;
    }
}
