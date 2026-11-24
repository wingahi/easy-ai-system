package com.wgh.rag.config.property;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 多路召回融合配置（前缀 wgh.retrieval.fusion）。
 *
 * <p>默认 k=60、向量路与关键词路权重均为 1.0，与引入权重前完全一致。</p>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "wgh.retrieval.fusion")
public class RetrievalFusionProperties {

    /** RRF 常数 k；越大越平滑，默认 60。 */
    private Integer rrfK = 60;

    /** 向量召回路的 RRF 权重，默认 1.0。 */
    private Double vectorWeight = 1.0;

    /** 关键词召回路的 RRF 权重，默认 1.0。 */
    private Double keywordWeight = 1.0;
}
