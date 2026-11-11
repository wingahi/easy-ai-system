package com.wgh.rag.config.property;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 评测相关配置（与 {@link RagProperties} 分离，避免与检索参数治理任务互相影响）。
 *
 * <p>所有配置均有默认值，缺失时不影响启动与主链路。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "wgh.rag.evaluation")
public class RagEvaluationProperties {

    /**
     * 单个 judge 调用超时（秒）。超时按该 judge 失败处理，不影响主链路。
     */
    private Integer judgeTimeoutSeconds = 30;

    /**
     * judge 并发度（线程数），即同时评估的 judge 数量上限。
     */
    private Integer judgeConcurrency = 4;

    /**
     * 每个 judge 的重复评估次数，最终取多次有效结果的均值；默认 1 次。
     */
    private Integer repeatCount = 1;

    /**
     * 单次离线检索评测最多处理的标注查询数，防止大样本评测长时间占用线程。
     */
    private Integer maxQueriesPerEvaluation = 200;
}
