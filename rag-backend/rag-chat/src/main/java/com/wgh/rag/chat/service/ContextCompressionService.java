package com.wgh.rag.chat.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.wgh.rag.chat.entity.ChatSession;
import com.wgh.rag.chat.mapper.ChatSessionMapper;
import com.wgh.rag.config.property.RagProperties;
import com.wgh.rag.llm.entity.LlmModel;
import com.wgh.rag.llm.factory.ModelCacheManager;
import com.wgh.rag.llm.service.LlmModelService;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * 上下文压缩服务 - 分层压缩策略（带持久化）
 *
 * <pre>
 * 三层压缩：近期原文 + 中期摘要 + 远期事实
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

    /** 分层压缩线程池 */
    private final ExecutorService summaryExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "ctx-compress-" + System.nanoTime());
        t.setDaemon(true);
        return t;
    });

    // ============ 依赖注入字段（允许 null，测试时自动走 fallback） ============

    private final RagProperties ragProperties;
    private final ModelCacheManager modelCacheManager;
    private final LlmModelService llmModelService;
    private final ChatSessionMapper chatSessionMapper;

    /** Spring 注入构造器（运行时） */
    @Autowired
    public ContextCompressionService(RagProperties ragProperties,
                                     ModelCacheManager modelCacheManager,
                                     LlmModelService llmModelService,
                                     ChatSessionMapper chatSessionMapper) {
        this.ragProperties = ragProperties;
        this.modelCacheManager = modelCacheManager;
        this.llmModelService = llmModelService;
        this.chatSessionMapper = chatSessionMapper;
    }

    /** 无参构造器（单元测试用，所有压缩都会降级到简单截断） */
    public ContextCompressionService() {
        this.ragProperties = null;
        this.modelCacheManager = null;
        this.llmModelService = null;
        this.chatSessionMapper = null;
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

        RagProperties.Context ctx = ragProperties.getContext();

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

        int recentRounds = ctx.getRecentWindowRounds() != null ? ctx.getRecentWindowRounds() : 4;
        int midRounds = ctx.getMidTermWindowRounds() != null ? ctx.getMidTermWindowRounds() : 6;

        if (rounds.size() <= recentRounds) {
            log.debug("对话轮次 {} <= 近期窗口 {}，仅截断不压缩", rounds.size(), recentRounds);
            return fallbackTruncate(messages, maxHistory);
        }

        // 3. 分层切分：从后往前数
        int recentStart = rounds.size() - recentRounds;
        int midStart = Math.max(0, recentStart - midRounds);

        List<Round> longTerm = rounds.subList(0, midStart);
        List<Round> midTerm = rounds.subList(midStart, recentStart);
        List<Round> recent = rounds.subList(recentStart, rounds.size());

        log.info("上下文分层压缩：总轮次={}, 远期={}, 中期={}, 近期={}",
                rounds.size(), longTerm.size(), midTerm.size(), recent.size());

        // 4. 检查已有压缩结果能否复用
        ChatSession savedSession = loadSession(sessionId);
        CompressionReuseCheck reuse = checkReuse(savedSession, midStart, recentStart);

        LlmModel compressionModel = reuse.allReusable() ? null : resolveCompressionModel();

        // 5. LLM 摘要（只对需要重新生成的层调用 LLM）
        String midSummary = reuse.savedMidSummary;
        String longTermFacts = reuse.savedLongTermFacts;

        try {
            if (compressionModel != null) {
                ChatModel chatModel = modelCacheManager.getChatModel(compressionModel.getId());
                int timeout = ctx.getSummaryTimeoutSeconds() != null ? ctx.getSummaryTimeoutSeconds() : 20;

                // 中期摘要
                if (!reuse.midReusable && !midTerm.isEmpty()) {
                    String prompt = buildMidSummaryPrompt(midTerm);
                    midSummary = callWithTimeout(chatModel, prompt, timeout);
                }

                // 远期事实提取
                if (!reuse.longTermReusable && !longTerm.isEmpty()) {
                    // 如果已有中期摘要需要"晋升"为远期事实，也要处理
                    List<Round> needExtract = new ArrayList<>(longTerm);
                    // 如果已有 midSummary 覆盖了更老的范围但现在需要 longTerm 包含它，也不在这里合并
                    // 简化：只对新的 longTerm 范围提取事实
                    String prompt = buildLongTermFactsPrompt(needExtract);
                    longTermFacts = callWithTimeout(chatModel, prompt, timeout);
                }
            } else if (!longTerm.isEmpty() || !midTerm.isEmpty()) {
                log.warn("未配置压缩专用模型（compressionModel=true），跳过 LLM 摘要");
            }
        } catch (Exception e) {
            log.error("LLM 摘要失败，降级为简单截断", e);
            return fallbackTruncate(messages, maxHistory);
        }

        // 6. 组装结果
        List<ChatMessage> result = assembleResult(systemMsg, longTermFacts, midSummary, recent);

        // 7. 保存压缩结果（有新生成且有 sessionId 时）
        if (sessionId != null && chatSessionMapper != null) {
            boolean needSave = !reuse.midReusable || !reuse.longTermReusable;
            if (needSave) {
                saveCompression(sessionId, midStart, recentStart, rounds.size(),
                        midSummary, longTermFacts, compressionModel);
            }
        }

        log.info("分层压缩完成: {} -> {} messages{}", messages.size(), result.size(),
                reuse.allReusable() ? "（全部复用已有压缩）" : "");
        return result;
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
                                               List<Round> recent) {
        List<ChatMessage> result = new ArrayList<>();
        if (systemMsg != null) result.add(systemMsg);

        if (longTermFacts != null && !longTermFacts.isBlank()) {
            result.add(SystemMessage.from("【远期对话关键事实】\n" + longTermFacts));
        }
        if (midSummary != null && !midSummary.isBlank()) {
            result.add(SystemMessage.from("【中期对话进展摘要】\n" + midSummary));
        }

        for (Round r : recent) {
            result.add(r.user);
            if (r.ai != null) result.add(r.ai);
        }
        return result;
    }

    // ==================== LLM 调用 & Prompt ====================

    private List<Round> pairIntoRounds(List<ChatMessage> dialog) {
        List<Round> rounds = new ArrayList<>();
        Round current = null;
        for (ChatMessage m : dialog) {
            if (m.type() == ChatMessageType.USER) {
                if (current != null) rounds.add(current);
                current = new Round((UserMessage) m, null);
            } else if (m.type() == ChatMessageType.AI && current != null) {
                current.ai = (AiMessage) m;
                rounds.add(current);
                current = null;
            }
        }
        if (current != null) rounds.add(current);
        return rounds;
    }

    private String buildMidSummaryPrompt(List<Round> midTerm) {
        String history = renderRounds(midTerm);
        int maxTokens = ragProperties.getContext().getSummaryMaxTokens() != null
                ? ragProperties.getContext().getSummaryMaxTokens() : 600;
        return """
                你是对话历史压缩器。以下是一段对话历史，请生成简洁的进展摘要（3-5 句）。

                【保留优先级（从高到低）】
                1. 用户明确的需求、决策、指令
                2. 已确认的业务规则或事实
                3. 实体名称（人名、产品名、文件名、配置项）
                4. 时间、数字、ID 等精确数据
                5. 讨论中的假设、未解决问题
                6. 背景闲聊、礼貌用语、重复表述（可删除）

                【输出要求】
                - 直接给摘要正文，不要输出"摘要："等前缀
                - 用陈述句，每句一行，或用分号连接
                - 总长度控制在 %d 以内

                【对话历史】
                %s
                """.formatted(maxTokens, history);
    }

    private String buildLongTermFactsPrompt(List<Round> longTerm) {
        String history = renderRounds(longTerm);
        int maxTokens = ragProperties.getContext().getLongTermFactsTokens() != null
                ? ragProperties.getContext().getLongTermFactsTokens() : 400;
        return """
                你是对话历史压缩器。以下是更早的一段对话历史，请只提取关键事实点，每条一行。

                【保留优先级（只留前 4 项）】
                1. 用户明确的需求、决策、指令
                2. 已确认的业务规则或事实
                3. 实体名称（人名、产品名、文件名、配置项）
                4. 时间、数字、ID 等精确数据
                （闲聊、过程性对话、礼貌用语全部丢弃）

                【输出要求】
                - 每行一个事实点，格式："- [事实要点]"
                - 不超过 8 条，只留不可丢失的信息
                - 总长度控制在 %d 以内

                【对话历史】
                %s
                """.formatted(maxTokens, history);
    }

    private String renderRounds(List<Round> rounds) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rounds.size(); i++) {
            Round r = rounds.get(i);
            sb.append("【第 ").append(i + 1).append(" 轮】\n");
            sb.append("用户: ").append(contentOf(r.user)).append("\n");
            if (r.ai != null) {
                sb.append("助手: ").append(contentOf(r.ai)).append("\n");
            }
        }
        return sb.toString();
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

    private String callWithTimeout(ChatModel model, String prompt, int timeoutSeconds) throws Exception {
        CompletableFuture<String> future = CompletableFuture.supplyAsync(
                () -> model.chat(prompt), summaryExecutor);
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new TimeoutException("摘要生成超时 " + timeoutSeconds + "s");
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

    /**
     * 轮次：一对 user + ai（ai 可能为 null 表示最后一轮用户还没收到回复）
     */
    private static class Round {
        final UserMessage user;
        AiMessage ai;

        Round(UserMessage user, AiMessage ai) {
            this.user = user;
            this.ai = ai;
        }
    }
}
