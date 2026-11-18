package com.wgh.rag.agentmulti.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 多 Agent 上下文相关配置（全部提供代码默认值，不依赖 application*.yml）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "wgh.multi-agent.context")
public class MultiAgentContextProperties {

    /** 汇总 prompt 的卡片预算 Tokens */
    private int summaryMaxTokens = 2000;

    /** 单张卡片结论预算 Tokens */
    private int cardConclusionMaxTokens = 200;

    /** hasMemory 注入的最近消息条数 */
    private int memoryMaxMessages = 6;

    /** hasMemory 注入的历史 Token 上限 */
    private int memoryMaxTokens = 1000;
}
