package com.wgh.rag.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "wgh.agent")
public class AgentProperties {
    /** Whether Agent auto-planning is enabled globally (per-window setting overrides). */
    private boolean enabled = false;
    /** Max plan steps (fallback; per-window agentMaxSteps overrides). */
    private int maxSteps = 15;
    /** Max tool rounds per step. */
    private int maxToolRoundsPerStep = 5;
    /** Default reflection retry count (when window has 0 or unset). */
    private int defaultReflectionRetries = 3;
    /** Default reflection score threshold 0-10. */
    private double defaultReflectionThreshold = 7.0;
    /** Whether reflection loop is enabled globally. */
    private boolean reflectionEnabled = true;
    /** Planner prompt version (bump to invalidate cached plans). */
    private String plannerPromptVersion = "v1";
    /** Agent 状态块（已完成步骤）总预算 Tokens，超预算优先丢弃更早步骤。 */
    private int agentStepContextMaxTokens = 2000;
    /** 单个步骤结论摘要预算 Tokens。 */
    private int agentStepSummaryMaxTokens = 200;
}
