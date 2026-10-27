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

    @Data
    public static class Retrieve {
        /** 检索返回 topK */
        private Integer topK = 5;
        /** 相似度阈值（0-1） */
        private Double minScore = 0.3;
        /** 是否开启重排 */
        private Boolean enableRerank = false;
    }

    @Data
    public static class Split {
        /** 默认最大段落长度（递归分块基线推荐 512） */
        private Integer maxLength = 512;
        /** 默认段落重叠长度（推荐 64） */
        private Integer overlapLength = 64;
        /** 默认分割方式：AUTO / RECURSIVE / CHAPTER / PARAGRAPH / FAQ / LENGTH / CUSTOM */
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
    }
}
