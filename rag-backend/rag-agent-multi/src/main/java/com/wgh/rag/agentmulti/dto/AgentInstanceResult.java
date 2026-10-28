package com.wgh.rag.agentmulti.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 单个 Agent 执行一步后的结构化结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentInstanceResult {

    private String output;

    private String thought;

    private List<ToolInvocation> toolInvocations;

    private List<RagSource> ragSources;

    /** Reflection 打分，null 表示未启用 */
    private Double reflectionScore;

    private String reflectionFeedback;

    /** Reflection 是否放行通过，null 表示未启用 */
    private Boolean reflectionPass;

    private Integer tokenCount;

    private Long durationMs;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToolInvocation {
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
    public static class RagSource {
        private String fileName;
        private Integer chunkIndex;
        private Double score;
        private String content;
    }
}
