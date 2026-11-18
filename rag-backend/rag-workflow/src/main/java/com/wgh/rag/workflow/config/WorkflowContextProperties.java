package com.wgh.rag.workflow.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 工作流节点上下文预算配置（{@code wgh.workflow.context}）。
 *
 * <p>所有字段均有代码默认值，application*.yml 未配置时直接使用默认值。
 */
@Data
@Component
@ConfigurationProperties(prefix = "wgh.workflow.context")
public class WorkflowContextProperties {

    /** LLM 节点输入预算占模型窗口的比例，默认 0.7 */
    private double nodePromptMaxRatio = 0.7;

    /** 模型未配置窗口大小时的兜底窗口（Tokens） */
    private int fallbackContextWindowTokens = 8192;

    /** 单节点输出落库上限（字节），默认 64KB；超限只落摘要 + truncated 标记 */
    private int nodeOutputMaxBytes = 65536;

    /** context_json 落库总上限（字节），默认 256KB；超限按早期节点优先摘要/剔除 nodeOutputs */
    private int contextJsonMaxBytes = 262144;
}
