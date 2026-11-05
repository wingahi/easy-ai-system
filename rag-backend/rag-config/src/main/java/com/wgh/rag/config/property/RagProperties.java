package com.wgh.rag.config.property;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * RAG 相关配置属性
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "wgh.rag")
public class RagProperties {

    /**
     * 检索相关
     */
    private Retrieve retrieve = new Retrieve();

    /**
     * 分割相关
     */
    private Split split = new Split();

    /**
     * 上下文压缩
     */
    private Context context = new Context();

    /**
     * 语义缓存
     */
    private Cache cache = new Cache();

    @Data
    public static class Retrieve {
        /** 检索返回 topK */
        private Integer topK = 5;
        /** 相似度阈值（0-1） */
        private Double minScore = 0.3;
        /** 是否开启重排（全局默认，窗口级 rerankEnabled 优先） */
        private Boolean enableRerank = false;
        /** 全局默认 Rerank 模型 ID（窗口未配置 rerankModelId 时回退） */
        private Long rerankModelId;
        /** 是否开启混合检索（向量 + 关键词 RRF 融合，全局默认，窗口级优先） */
        private Boolean enableHybrid = false;
        /** 混合检索关键词路召回条数 */
        private Integer keywordTopN = 10;
    }

    @Data
    public static class Split {
        /** 默认最大段落长度（递归分块基线推荐 512） */
        private Integer maxLength = 512;
        /** 默认段落重叠长度（推荐 64） */
        private Integer overlapLength = 64;
        /** 默认分割方式：AUTO / RECURSIVE / CHAPTER / PARAGRAPH / FAQ / LENGTH / CUSTOM / STRUCTURED / SEMANTIC */
        private String defaultStrategy = "AUTO";
    }

    @Data
    public static class Context {
        /** 最大历史消息条数（触发压缩阈值） */
        private Integer maxHistoryMessages = 20;
        /** 最大历史Token数（估算值） */
        private Integer maxHistoryTokens = 4000;
        /** 是否启用上下文压缩 */
        private Boolean enableCompression = true;

        // ============ 分层压缩配置 ============

        /** 分层压缩：近期窗口大小（轮，保留原文） */
        private Integer recentWindowRounds = 4;
        /** 分层压缩：中期窗口大小（轮，做摘要） */
        private Integer midTermWindowRounds = 6;
        /** 分层压缩：远期对话 Token 预算（仅提取关键事实） */
        private Integer longTermFactsTokens = 400;
        /** 分层压缩：LLM 摘要最大输出 Token（避免摘要本身过长） */
        private Integer summaryMaxTokens = 600;
        /** 分层压缩：摘要 LLM 调用超时（秒） */
        private Integer summaryTimeoutSeconds = 20;

        // ============ 检索上下文 Token 预算 ============

        /** 检索上下文 Token 硬上限（默认 6000：5×512 字 ≈ 2500 Tokens，留约 2.4 倍余量） */
        private Integer contextMaxTokens = 6000;
        /** 输入侧最多占模型窗口比例，其余留给输出与估算误差（默认 0.6） */
        private Double contextWindowRatio = 0.6;
        /** 模型未配置 maxContextTokens 时的兜底窗口（默认 8192，兼容 8k 上下文模型） */
        private Integer fallbackWindowTokens = 8192;
        /** 预算耗尽时单条 chunk 的最小保留 Token（默认 120，避免整条上下文丢失） */
        private Integer minChunkTokens = 120;
    }

    @Data
    public static class Cache {
        /** 语义缓存全局开关（窗口级 semanticCacheEnabled 需同时开启） */
        private Boolean enabled = true;
        /** 命中阈值（余弦相似度 0-1，越高越严格） */
        private Double similarityThreshold = 0.95;
        /** 每窗口最大缓存条数，超出删除最旧 */
        private Integer maxPerWindow = 500;
        /** 缓存有效期（分钟，<=0 表示不过期） */
        private Integer ttlMinutes = 1440;
        /** 过期缓存单次清理条数（避免一次删除过多） */
        private Integer cleanupBatchSize = 200;
    }
}
