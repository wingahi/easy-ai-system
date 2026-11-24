package com.wgh.rag.config.property;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * 关键词检索后端选择配置（前缀 wgh.search）。
 *
 * <p>默认 backend=pgtrgm，行为与现有混合检索关键词路完全一致。</p>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "wgh.search")
public class KeywordSearchProperties {

    /** 当前关键词后端：pgtrgm（默认）/ memory（仅开发测试）。 */
    private String backend = "pgtrgm";

    /** 主后端异常时的降级后端；留空表示不降级。 */
    private String fallbackBackend = "pgtrgm";

    /** 是否允许关键词路异常时降级到 fallback-backend；关闭时异常向上抛出由编排层记录。 */
    private Boolean degradeOnError = true;

    /**
     * 双读对比开关：开启后同一查询在主后端与 compare-backend 上都执行，
     * 只记录差异指标与日志，返回结果仍取主后端结果。
     */
    private Boolean compareEnabled = false;

    /** 双读对比的对照后端；默认 pgtrgm（现有实现），与主后端同名时跳过对比。 */
    private String compareBackend = "pgtrgm";

    /**
     * 灰度后端：命中 {@link #grayKbIds} 的知识库走该后端；留空表示未启用灰度。
     * <p>当前仓库只有 pgtrgm 实现，接入独立搜索引擎后只需配置此值与白名单即可灰度。</p>
     */
    private String grayBackend = "";

    /** 灰度知识库白名单（KB ID）。 */
    private List<Long> grayKbIds = new ArrayList<>();
}
