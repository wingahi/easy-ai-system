package com.wgh.rag.chat.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.wgh.rag.chat.compression.CompressionContext;
import com.wgh.rag.chat.compression.CompressionHandler;
import com.wgh.rag.chat.compression.FactExtractHandler;
import com.wgh.rag.chat.compression.RecentKeepHandler;
import com.wgh.rag.chat.compression.Round;
import com.wgh.rag.chat.compression.SummaryHandler;
import com.wgh.rag.chat.entity.ChatSession;
import com.wgh.rag.chat.mapper.ChatSessionMapper;
import com.wgh.rag.config.property.RagProperties;
import com.wgh.rag.llm.entity.LlmModel;
import com.wgh.rag.llm.factory.ModelCacheManager;
import com.wgh.rag.llm.service.LlmModelService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 上下文压缩服务 - 分层压缩策略（带持久化），责任链编排入口
 *
 * <pre>
 * 三层压缩：近期原文 + 中期摘要 + 远期事实
 * 责任链：{@link RecentKeepHandler} → {@link SummaryHandler} → {@link FactExtractHandler}，
 * 按 order() 升序执行，层级可插拔；每级降级策略封装在各自 handler 内。
 *
 * 消息分层（按 user+ai 配对为轮次）：
 *   ┌─────────────────────────────────────────────────────────────────┐
 *   │  System（系统提示）                                             │
 *   ├─────────────────────────────────────────────────────────────────┤
 *   │  远期事实 SystemMessage（若存在）  ↑ 更早的对话，只提取关键事实 │
 *   ├─────────────────────────────────────────────────────────────────┤
 *   │  中期摘要 SystemMessage（若存在）  ↑ 中间段，LLM 生成摘要       │
 *   ├─────────────────────────────────────────────────────────────────┤
 *   │  近期原文 user/ai/user/ai/...     ↑ 最近 N 轮，完整保留原文   │
 *   └─────────────────────────────────────────────────────────────────┘
 *
 * 持久化策略（参照设计文档）：
 *   层次       存储位置          复用判断
 *   近期原文    已在 wgh_chat_message   -
 *   中期摘要    wgh_chat_session 列     覆盖范围 + prompt 版本匹配 → 复用
 *   远期事实    wgh_chat_session 列     覆盖范围 + prompt 版本匹配 → 复用
 *
 * 摘要 Prompt 显式定义保留优先级，避免 LLM 随意裁剪关键信息。
 * 失败兜底：LLM 不可用或依赖未注入时 fallback 到简单截断。
 * </pre>
 */
@Slf4j
@Service
public class ContextCompressionService {

    /** Prompt 版本号 — 变更摘要/事实 prompt 时 bump 此值，使旧压缩自动失效重算 */
    private static final String PROMPT_VERSION = "v1";

    // ============ 依赖注入字段（允许 null，测试时自动走 fallback） ============

    private final RagProperties ragProperties;
    private final ModelCacheManager modelCacheManager;
    private final LlmModelService llmModelService;
    private final ChatSessionMapper chatSessionMapper;
    /** 压缩责任链（按 order() 升序） */
    private final List<CompressionHandler> compressionHandlers;

    /** Spring 注入构造器（运行时）：注入所有压缩处理器并按 order() 排序成链 */
    @Autowired
    public ContextCompressionService(RagProperties ragProperties,
                                     ModelCacheManager modelCacheManager,
                                     LlmModelService llmModelService,
                                     ChatSessionMapper chatSessionMapper,
                                     List<CompressionHandler> compressionHandlers) {
        this.ragProperties = ragProperties;
        this.modelCacheManager = modelCacheManager;
        this.llmModelService = llmModelService;
        this.chatSessionMapper = chatSessionMapper;
        this.compressionHandlers = sortedChain(compressionHandlers);
    }

    /** 兼容构造器：不显式提供处理器时使用默认三级责任链 */
    public ContextCompressionService(RagProperties ragProperties,
                                     ModelCacheManager modelCacheManager,
                                     LlmModelService llmModelService,
                                     ChatSessionMapper chatSessionMapper) {
        this(ragProperties, modelCacheManager, llmModelService, chatSessionMapper, defaultChain());
    }

    /** 无参构造器（单元测试用，所有压缩都会降级到简单截断） */
    public ContextCompressionService() {
        this(null, null, null, null, List.of());
    }

    private static List<CompressionHandler> defaultChain() {
        return List.of(new RecentKeepHandler(), new SummaryHandler(), new FactExtractHandler());
    }

    private static List<CompressionHandler> sortedChain(List<CompressionHandler> handlers) {
        List<CompressionHandler> sorted = new ArrayList<>(handlers);
        sorted.sort(Comparator.comparingInt(CompressionHandler::order));
        return List.copyOf(sorted);
    }

    // ==================== 公开入口 ====================

    /**
     * 分层压缩入口（无 session 版，不持久化，用于测试或不需要持久化的场景）
     */
    public List<ChatMessage> compressHistory(List<ChatMessage> messages, int maxHistory) {
        return compressHistory(messages, maxHistory, null);
    }

    /**
     * 分层压缩入口（带 session 持久化）
     *
     * @param messages   原始消息列表（含 System）
     * @param maxHistory 触发压缩阈值（条数）
     * @param sessionId  聊天会话 ID，用于读写已保存的压缩结果；null 则不持久化
     * @return 压缩后的消息列表
     */
    public List<ChatMessage> compressHistory(List<ChatMessage> messages, int maxHistory, Long sessionId) {
        if (messages.size() <= maxHistory) {
            return messages;
        }

        log.debug("compressHistory 被调用: input={}, maxHistory={}, sessionId={}",
                messages.size(), maxHistory, sessionId);

        // 依赖未注入（单元测试）时直接 fallback 简单截断
        if (ragProperties == null || modelCacheManager == null || llmModelService == null) {
            return fallbackTruncate(messages, maxHistory);
        }

        RagProperties.Context ctxProps = ragProperties.getContext();

        // 1. 分离 System 和对话轮次
        SystemMessage systemMsg = null;
        List<ChatMessage> dialog = new ArrayList<>();
        for (ChatMessage m : messages) {
            if (m.type() == ChatMessageType.SYSTEM && systemMsg == null) {
                systemMsg = (SystemMessage) m;
            } else {
                dialog.add(m);
            }
        }

        // 2. 按 user+ai 配对成轮次
        List<Round> rounds = pairIntoRounds(dialog);
        if (rounds.isEmpty()) {
            return fallbackTruncate(messages, maxHistory);
        }

        int recentRounds = ctxProps.getRecentWindowRounds() != null ? ctxProps.getRecentWindowRounds() : 4;
        int midRounds = ctxProps.getMidTermWindowRounds() != null ? ctxProps.getMidTermWindowRounds() : 6;

        if (rounds.size() <= recentRounds) {
            log.debug("对话轮次 {} <= 近期窗口 {}，仅截断不压缩", rounds.size(), recentRounds);
            return fallbackTruncate(messages, maxHistory);
        }

        // 3. 分层切分：从后往前数
        int recentStart = rounds.size() - recentRounds;
        int midStart = Math.max(0, recentStart - midRounds);

        log.info("上下文分层压缩：总轮次={}, 远期={}, 中期={}, 近期={}",
                rounds.size(), midStart, recentStart - midStart, rounds.size() - recentStart);

        // 4. 检查已有压缩结果能否复用
        ChatSession savedSession = loadSession(sessionId);
        CompressionReuseCheck reuse = checkReuse(savedSession, midStart, recentStart);

        LlmModel compressionModel = reuse.allReusable() ? null : resolveCompressionModel();

        // 5. 构建压缩上下文并执行责任链（各级的复用/降级由 handler 自行处理）
        CompressionContext compressionContext = buildCompressionContext(
                messages, maxHistory, sessionId, systemMsg, rounds,
                recentRounds, midRounds, midStart, recentStart, ctxProps, compressionModel, reuse);

        runCompressionChain(compressionContext);

        // 任一级 LLM 失败 → 整体降级为简单截断
        if (compressionContext.isDegraded()) {
            return fallbackTruncate(messages, maxHistory);
        }

        // 6. 组装结果
        List<ChatMessage> result = assembleResult(systemMsg,
                compressionContext.getLongTermFacts(), compressionContext.getMidSummary(),
                compressionContext.getRecentMessages());

        // 7. 保存压缩结果（有新生成且有 sessionId 时）
        if (sessionId != null && chatSessionMapper != null) {
            boolean needSave = !reuse.midReusable() || !reuse.longTermReusable();
            if (needSave) {
                saveCompression(sessionId, midStart, recentStart, rounds.size(),
                        compressionContext.getMidSummary(), compressionContext.getLongTermFacts(), compressionModel);
            }
        }

        log.info("分层压缩完成: {} -> {} messages{}", messages.size(), result.size(),
                reuse.allReusable() ? "（全部复用已有压缩）" : "");
        return result;
    }

    /**
     * 构建压缩上下文：分层结构、配置、复用状态、LLM 模型与输出初始值
     */
    private CompressionContext buildCompressionContext(List<ChatMessage> messages, int maxHistory, Long sessionId,
                                                       SystemMessage systemMsg, List<Round> rounds,
                                                       int recentRounds, int midRounds, int midStart, int recentStart,
                                                       RagProperties.Context ctxProps, LlmModel compressionModel,
                                                       CompressionReuseCheck reuse) {
        CompressionContext ctx = new CompressionContext();
        ctx.setOriginalMessages(messages);
        ctx.setMaxHistory(maxHistory);
        ctx.setSessionId(sessionId);
        ctx.setSystemMessage(systemMsg);
        ctx.setRounds(rounds);
        ctx.setRecentWindowSize(recentRounds);
        ctx.setMidWindowSize(midRounds);
        ctx.setMidStart(midStart);
        ctx.setRecentStart(recentStart);
        ctx.setConfig(ctxProps);
        ctx.setCompressionModel(compressionModel);
        ctx.setTimeoutSeconds(ctxProps.getSummaryTimeoutSeconds() != null ? ctxProps.getSummaryTimeoutSeconds() : 20);
        ctx.setMidReusable(reuse.midReusable());
        ctx.setLongTermReusable(reuse.longTermReusable());
        ctx.setSavedMidSummary(reuse.savedMidSummary());
        ctx.setSavedLongTermFacts(reuse.savedLongTermFacts());
        // 输出初始化为已保存值：不可复用且该级未重新生成时保持原语义（沿用已保存内容）
        ctx.setMidSummary(reuse.savedMidSummary());
        ctx.setLongTermFacts(reuse.savedLongTermFacts());

        if (compressionModel != null) {
            try {
                ctx.setChatModel(modelCacheManager.getChatModel(compressionModel.getId()));
            } catch (Exception e) {
                log.error("获取压缩模型失败，降级为简单截断", e);
                ctx.setDegraded(true);
            }
        }
        return ctx;
    }

    /**
     * 按 order() 升序依次执行责任链；任一级标记降级后立即中断
     */
    private void runCompressionChain(CompressionContext ctx) {
        for (CompressionHandler handler : compressionHandlers) {
            if (ctx.isDegraded()) {
                break;
            }
            if (handler.shouldHandle(ctx)) {
                handler.handle(ctx);
            }
        }
    }

    // ==================== 持久化相关 ====================

    /**
     * 加载 session 已有压缩结果
     */
    private ChatSession loadSession(Long sessionId) {
        if (sessionId == null || chatSessionMapper == null) return null;
        try {
            return chatSessionMapper.selectById(sessionId);
        } catch (Exception e) {
            log.warn("加载 session {} 压缩结果失败: {}", sessionId, e.getMessage());
            return null;
        }
    }

    /**
     * 判断已保存的压缩结果能否复用
     */
    private CompressionReuseCheck checkReuse(ChatSession s, int midStart, int recentStart) {
        boolean canReuseMid = false;
        boolean canReuseLong = false;
        String savedMid = null;
        String savedLong = null;

        if (s != null) {
            // 远期事实复用：已覆盖范围 >= 当前需要，且 prompt 版本匹配
            if (s.getLongTermFacts() != null
                    && s.getLongTermFactsRoundEnd() != null
                    && s.getLongTermFactsRoundEnd() >= midStart
                    && PROMPT_VERSION.equals(s.getLongTermFactsPromptVersion())) {
                canReuseLong = true;
                savedLong = s.getLongTermFacts();
                log.info("远期事实复用已有压缩: round_end={}, 需要覆盖<{}",
                        s.getLongTermFactsRoundEnd(), midStart);
            }

            // 中期摘要复用：范围完全匹配 + prompt 版本匹配
            if (s.getMidSummary() != null
                    && s.getMidSummaryRoundStart() != null
                    && s.getMidSummaryRoundEnd() != null
                    && s.getMidSummaryRoundStart() == midStart
                    && s.getMidSummaryRoundEnd() == recentStart
                    && PROMPT_VERSION.equals(s.getMidSummaryPromptVersion())) {
                canReuseMid = true;
                savedMid = s.getMidSummary();
                log.info("中期摘要复用已有压缩: [{}, {})", midStart, recentStart);
            }
        }

        return new CompressionReuseCheck(canReuseMid, canReuseLong, savedMid, savedLong);
    }

    /**
     * 保存压缩结果到 session
     */
    private void saveCompression(Long sessionId, int midStart, int recentStart, int totalRounds,
                                  String midSummary, String longTermFacts, LlmModel model) {
        try {
            UpdateWrapper<ChatSession> uw = new UpdateWrapper<>();
            uw.eq("id", sessionId);
            int affected = 0;

            boolean needMid = midSummary != null && !midSummary.isBlank();
            boolean needLong = longTermFacts != null && !longTermFacts.isBlank();

            if (needMid) {
                uw.set("mid_summary", midSummary);
                uw.set("mid_summary_round_start", midStart);
                uw.set("mid_summary_round_end", recentStart);
                uw.set("mid_summary_model", model != null ? model.getModelName() : null);
                uw.set("mid_summary_prompt_version", PROMPT_VERSION);
                uw.set("mid_summary_time", LocalDateTime.now());
                affected++;
            }

            if (needLong) {
                uw.set("long_term_facts", longTermFacts);
                uw.set("long_term_facts_round_end", midStart);
                uw.set("long_term_facts_model", model != null ? model.getModelName() : null);
                uw.set("long_term_facts_prompt_version", PROMPT_VERSION);
                uw.set("long_term_facts_time", LocalDateTime.now());
                affected++;
            }

            if (affected > 0) {
                chatSessionMapper.update(null, uw);
                log.info("压缩结果已保存到 session {}: midSummary={}, longTermFacts={}",
                        sessionId, needMid, needLong);
            }
        } catch (Exception e) {
            log.warn("保存压缩结果失败（不影响主流程）: sessionId={}, err={}",
                    sessionId, e.getMessage());
        }
    }

    /**
     * 组装最终消息列表
     */
    private List<ChatMessage> assembleResult(SystemMessage systemMsg,
                                               String longTermFacts, String midSummary,
                                               List<ChatMessage> recentMessages) {
        List<ChatMessage> result = new ArrayList<>();
        if (systemMsg != null) result.add(systemMsg);

        if (longTermFacts != null && !longTermFacts.isBlank()) {
            result.add(SystemMessage.from("【远期对话关键事实】\n" + longTermFacts));
        }
        if (midSummary != null && !midSummary.isBlank()) {
            result.add(SystemMessage.from("【中期对话进展摘要】\n" + midSummary));
        }

        result.addAll(recentMessages);
        return result;
    }

    // ==================== 轮次配对 & 模型解析 ====================

    private List<Round> pairIntoRounds(List<ChatMessage> dialog) {
        List<Round> rounds = new ArrayList<>();
        Round current = null;
        for (ChatMessage m : dialog) {
            if (m.type() == ChatMessageType.USER) {
                if (current != null) rounds.add(current);
                current = new Round((UserMessage) m, null);
            } else if (m.type() == ChatMessageType.AI && current != null) {
                current.setAi((AiMessage) m);
                rounds.add(current);
                current = null;
            }
        }
        if (current != null) rounds.add(current);
        return rounds;
    }

    private LlmModel resolveCompressionModel() {
        try {
            LlmModel m = llmModelService.findCompressionModel();
            if (m != null) return m;
            List<LlmModel> all = llmModelService.listEnabledChatModels();
            return all.isEmpty() ? null : all.get(0);
        } catch (Exception e) {
            log.warn("查找压缩模型失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 兜底：简单截断
     */
    private List<ChatMessage> fallbackTruncate(List<ChatMessage> original, int maxMessages) {
        if (original.size() <= maxMessages) return original;
        SystemMessage systemMsg = null;
        for (ChatMessage m : original) {
            if (m.type() == ChatMessageType.SYSTEM) { systemMsg = (SystemMessage) m; break; }
        }
        List<ChatMessage> result = new ArrayList<>();
        if (systemMsg != null) result.add(systemMsg);
        int keepFrom = original.size() - (maxMessages - 1);
        for (int i = keepFrom; i < original.size(); i++) {
            if (original.get(i).type() != ChatMessageType.SYSTEM) {
                result.add(original.get(i));
            }
        }
        log.warn("LLM 压缩不可用，降级简单截断: {} -> {} messages", original.size(), result.size());
        return result;
    }

    // ==================== Token 估算 ====================

    public List<ChatMessage> compressByToken(List<ChatMessage> messages, int maxTokens) {
        int currentTokens = 0;
        List<ChatMessage> result = new ArrayList<>();
        if (!messages.isEmpty()) {
            result.add(messages.get(0));
            currentTokens = estimateTokens(contentOf(messages.get(0)));
        }
        for (int i = messages.size() - 1; i >= 1; i--) {
            ChatMessage msg = messages.get(i);
            int tokens = estimateTokens(contentOf(msg));
            if (currentTokens + tokens > maxTokens) break;
            result.add(1, msg);
            currentTokens += tokens;
        }
        log.info("上下文 Token 压缩: tokens≈{} -> messages={}", currentTokens, result.size());
        return result;
    }

    private int estimateTokens(String text) {
        if (text == null) return 0;
        int chineseChars = 0;
        int otherChars = 0;
        for (char c : text.toCharArray()) {
            if (c >= 0x4E00 && c <= 0x9FFF) chineseChars++;
            else otherChars++;
        }
        return (int) (chineseChars * 1.5 + otherChars / 4.0);
    }

    private String contentOf(ChatMessage msg) {
        return switch (msg.type()) {
            case SYSTEM -> ((SystemMessage) msg).text();
            case USER -> ((UserMessage) msg).singleText();
            case AI -> ((AiMessage) msg).text();
            case TOOL_EXECUTION_RESULT -> ((ToolExecutionResultMessage) msg).text();
            default -> "";
        };
    }

    // ==================== 内部 Record / DTO ====================

    /**
     * 压缩复用检查结果
     */
    private record CompressionReuseCheck(boolean midReusable, boolean longTermReusable,
                                          String savedMidSummary, String savedLongTermFacts) {
        boolean allReusable() { return midReusable && longTermReusable; }
    }
}
