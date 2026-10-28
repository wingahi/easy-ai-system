package com.wgh.rag.agentmulti.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 单个 Agent 的运行时实例定义（配置快照）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentInstance {

    /** Agent 唯一标识 */
    private String agentId;

    private String name;

    /** 角色：如 researcher / writer / coder */
    private String role;

    private String goal;

    private String systemPrompt;

    /** 对话用模型 ID */
    private Long chatModelId;

    /** 关联知识库 ID 列表 */
    private List<Long> kbIds;

    /** 可用工具 ID 列表 */
    private List<String> toolIds;

    @Builder.Default
    private boolean hasMemory = false;

    @Builder.Default
    private boolean enableReflection = false;

    /** Reflection 使用的模型 ID（可选） */
    private Long reflectionModelId;

    /** Reflection 放行阈值，低于此值需重试或走 HITL */
    @Builder.Default
    private double reflectionThreshold = 7.0;

    @Builder.Default
    private boolean hitlRequired = false;

    private String hitlPrompt;
}
