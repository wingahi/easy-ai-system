package com.wgh.rag.agentmulti.dto;

import lombok.Data;

/**
 * 多 Agent Run 创建请求 —— Controller 完成参数解析后传入 Service 装配。
 */
@Data
public class RunCreateRequest {

    /** 由调用方生成（runtime.newRunId()），保持 Service 不依赖 Runtime 的单向链路 */
    private String runId;

    private Long sessionId;

    /** 为 null 时装配为 0L */
    private Long chatWindowId;

    /** 执行模式；crew 模板存在时以模板为准（现状行为） */
    private String executionMode;

    private Long crewId;

    private String input;

    private String userGoal;

    /** 已序列化的 JSON 字符串 */
    private String agentsJson;

    private String crewConfigJson;

    /** 为 null 时装配为 10 */
    private Integer maxRounds;

    /** GroupChat：LLM 动态路由模型 ID（可空） */
    private Long dynamicRouterModelId;

    /** GroupChat：动态路由 prompt（可空） */
    private String dynamicRouterPrompt;

    /** GroupChat：终止条件表达式（可空） */
    private String terminationCriteria;

    /** GroupChat：最终汇总 Agent ID（可空） */
    private String summarizerAgentId;
}
