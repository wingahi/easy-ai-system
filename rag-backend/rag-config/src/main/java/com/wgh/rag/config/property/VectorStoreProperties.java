package com.wgh.rag.config.property;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 向量后端选择与事实层同步配置（前缀 wgh.vector）。
 *
 * <p>默认 backend=pgvector，行为与仅使用 pgvector 时完全一致；切换后端只改配置。</p>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "wgh.vector")
public class VectorStoreProperties {

    /** 当前向量后端：pgvector（默认）/ memory（仅开发测试）。 */
    private String backend = "pgvector";

    /** 主后端异常时的降级后端；留空表示不降级。 */
    private String fallbackBackend = "";

    /** 是否允许主后端异常时降级到 fallback-backend。 */
    private Boolean degradeOnError = false;

    /**
     * 是否启用 chunk 事实层双写（wgh_kb_chunk）。
     * <p>默认关闭以保持现有入库行为不变；灰度时按需开启，并由 KB_VECTOR_BACKFILL 补偿。</p>
     */
    private Boolean factLayerEnabled = false;

    /** 事实层回填单批处理条数。 */
    private Integer factBackfillBatchSize = 200;
}
