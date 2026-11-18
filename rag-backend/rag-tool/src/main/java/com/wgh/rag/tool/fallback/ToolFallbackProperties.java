package com.wgh.rag.tool.fallback;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 工具调用兜底链配置（{@code wgh.tool.fallback}）。
 *
 * <p>所有字段均有代码默认值，application*.yml 未配置时直接使用默认值。
 */
@Data
@Component
@ConfigurationProperties(prefix = "wgh.tool.fallback")
public class ToolFallbackProperties {

    /** 兜底链总开关（关闭后退化为单次直调，不做重试/熔断/预算） */
    private boolean enabled = true;

    /** 单次工具调用最多尝试次数（含首次），默认 2 */
    private int maxAttempts = 2;

    /** 首次重试的退避基数（毫秒），默认 300 */
    private long backoffMs = 300;

    /** 退避抖动比例（±30%），避免重试尖峰 */
    private double jitterRatio = 0.3;

    /** 仅对幂等调用重试（HTTP GET/HEAD，或 executorConfig.idempotent=true） */
    private boolean idempotentOnly = true;

    /** 熔断：连续失败次数阈值 */
    private int failureThreshold = 5;

    /** 熔断打开时长（秒），到期后放行一个半开探针 */
    private long openSeconds = 30;

    /** 单任务（一次用户请求 / 一个 Agent 步骤 / 一次工作流运行）最大工具调用次数 */
    private int taskMaxRequests = 50;

    /** 单任务工具调用总时长预算（毫秒） */
    private long taskMaxTotalMs = 120_000;
}
