package com.wgh.rag.chat.compression;

import com.wgh.rag.config.property.RagProperties;
import com.wgh.rag.llm.entity.LlmModel;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.ChatModel;
import lombok.Data;

import java.util.List;

/**
 * 压缩上下文：责任链各级共享的输入、分层结构、配置、复用状态与输出。
 */
@Data
public class CompressionContext {

    // ============ 输入 ============

    /** 原始消息列表（含 System） */
    private List<ChatMessage> originalMessages;
    /** 触发压缩阈值（条数） */
    private int maxHistory;
    /** 聊天会话 ID，null 表示不持久化 */
    private Long sessionId;

    // ============ 分层结构 ============

    /** 首条系统提示 */
    private SystemMessage systemMessage;
    /** 全部对话轮次（user+ai 配对） */
    private List<Round> rounds;
    /** 近期窗口大小（轮，保留原文） */
    private int recentWindowSize;
    /** 中期窗口大小（轮，做摘要） */
    private int midWindowSize;
    /** 中期层起始轮下标（即远期层结束下标） */
    private int midStart;
    /** 近期层起始轮下标（即中期层结束下标） */
    private int recentStart;

    // ============ 配置与 LLM ============

    /** 上下文压缩配置 */
    private RagProperties.Context config;
    /** 压缩专用模型配置 */
    private LlmModel compressionModel;
    /** 压缩用 ChatModel，null 表示不可用 */
    private ChatModel chatModel;
    /** LLM 调用超时（秒） */
    private int timeoutSeconds;

    // ============ 复用判断 ============

    /** 中期摘要是否可复用已保存结果 */
    private boolean midReusable;
    /** 远期事实是否可复用已保存结果 */
    private boolean longTermReusable;
    private String savedMidSummary;
    private String savedLongTermFacts;

    // ============ 输出 ============

    /** 中期摘要文本（初始化为已保存值，handler 按需覆盖） */
    private String midSummary;
    /** 远期事实文本（初始化为已保存值，handler 按需覆盖） */
    private String longTermFacts;
    /** 近期原文消息 */
    private List<ChatMessage> recentMessages;

    /** 降级标记：任一级失败时置 true，由编排方统一降级为简单截断 */
    private boolean degraded;

    /** 远期层轮次 [0, midStart) */
    public List<Round> getLongTermLayer() {
        return rounds.subList(0, midStart);
    }

    /** 中期层轮次 [midStart, recentStart) */
    public List<Round> getMidLayer() {
        return rounds.subList(midStart, recentStart);
    }

    /** 近期层轮次 [recentStart, size) */
    public List<Round> getRecentLayer() {
        return rounds.subList(recentStart, rounds.size());
    }
}
