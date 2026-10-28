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
}
