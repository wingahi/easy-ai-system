package com.wgh.rag.llm.util;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 历史消息 Token 预算器（纯函数，无状态）。
 *
 * <p>用途：上下文压缩只按「条数 / 轮次」控制，单条超长消息仍可撑爆模型窗口。
 * 本类在压缩结果之上再做一次按 Token 的硬裁剪，规则：
 * <ol>
 *   <li>所有 {@link SystemMessage} 保留（首条为稳定前缀，其余为压缩生成的远期事实 / 中期摘要，信息密度高）；</li>
 *   <li>当前用户消息（列表最后一条）始终保留，即便单条就超出预算；</li>
 *   <li>其余历史从最近往前保留，遇到放不下的消息即停止，更早的消息全部丢弃；</li>
 *   <li>不改变保留消息的原始顺序。</li>
 * </ol>
 */
public final class HistoryContextBudgeter {

    /** 历史消息 Token 上限默认值（与 wgh.rag.context.max-history-tokens 默认值一致） */
    public static final int DEFAULT_MAX_HISTORY_TOKENS = 4000;

    /** 裁剪结果。 */
    public record Result(List<ChatMessage> kept, int originalTokens, int keptTokens, int droppedMessages) {
    }

    private HistoryContextBudgeter() {
    }

    /**
     * 按 Token 上限裁剪历史消息。
     *
     * @param messages        完整消息列表（含 System 与当前用户消息）
     * @param maxHistoryTokens 历史 Token 上限（非正数使用默认 4000）
     */
    public static Result trim(List<ChatMessage> messages, int maxHistoryTokens) {
        if (messages == null || messages.isEmpty()) {
            return new Result(messages == null ? List.of() : messages, 0, 0, 0);
        }
        int budget = maxHistoryTokens > 0 ? maxHistoryTokens : DEFAULT_MAX_HISTORY_TOKENS;

        int originalTokens = 0;
        int systemTokens = 0;
        for (ChatMessage message : messages) {
            int tokens = tokens(message);
            originalTokens += tokens;
            if (message.type() == ChatMessageType.SYSTEM) {
                systemTokens += tokens;
            }
        }

        int lastIndex = messages.size() - 1;
        boolean lastIsSystem = messages.get(lastIndex).type() == ChatMessageType.SYSTEM;

        // System 全部保留；当前用户消息优先占用预算（不足也保留）
        int remaining = Math.max(budget - systemTokens, 0);
        if (!lastIsSystem) {
            remaining = Math.max(remaining - tokens(messages.get(lastIndex)), 0);
        }

        // 从倒数第二条往前保留其余历史，遇到放不下的即停止
        int firstKept = messages.size();
        for (int i = lastIndex - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message.type() == ChatMessageType.SYSTEM) {
                continue;
            }
            int cost = tokens(message);
            if (cost > remaining) {
                break;
            }
            remaining -= cost;
            firstKept = i;
        }

        List<ChatMessage> kept = new ArrayList<>();
        int keptTokens = 0;
        int dropped = 0;
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage message = messages.get(i);
            boolean keep = message.type() == ChatMessageType.SYSTEM || i == lastIndex || i >= firstKept;
            if (keep) {
                kept.add(message);
                keptTokens += tokens(message);
            } else {
                dropped++;
            }
        }
        return new Result(List.copyOf(kept), originalTokens, keptTokens, dropped);
    }

    /** 单条消息的 Token 估算（多模态用户消息只计文本部分）。 */
    public static int tokens(ChatMessage message) {
        if (message == null || message.type() == null) {
            return 0;
        }
        return switch (message.type()) {
            case SYSTEM -> TokenEstimator.estimate(((SystemMessage) message).text());
            case AI -> TokenEstimator.estimate(((AiMessage) message).text());
            case TOOL_EXECUTION_RESULT -> TokenEstimator.estimate(((ToolExecutionResultMessage) message).text());
            case USER -> {
                UserMessage userMessage = (UserMessage) message;
                int total = 0;
                for (Content content : userMessage.contents()) {
                    if (content instanceof TextContent textContent) {
                        total += TokenEstimator.estimate(textContent.text());
                    }
                }
                yield total;
            }
            default -> 0;
        };
    }
}
