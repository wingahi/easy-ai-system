package com.wgh.rag.chat.service.flow;

import dev.langchain4j.model.output.TokenUsage;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单次对话的可变状态：工具调用轮次与 token 累计。
 *
 * <p>请求线程与流式回调线程共享，使用原子类型替代原有的 {@code int[]} / {@code long[]}
 * 位置约定，语义更明确。</p>
 */
final class ChatTurnState {

    private final AtomicInteger toolRounds = new AtomicInteger();
    private final AtomicLong inputTokens = new AtomicLong();
    private final AtomicLong outputTokens = new AtomicLong();

    void resetToolRounds() {
        toolRounds.set(0);
    }

    int nextToolRound() {
        return toolRounds.incrementAndGet();
    }

    int toolRounds() {
        return toolRounds.get();
    }

    /** 累计真实 token 用量（提供商未下发 usage 时保持为 0，由成本结算按字符估算兜底）。 */
    void addUsage(TokenUsage usage) {
        if (usage == null) {
            return;
        }
        if (usage.inputTokenCount() != null) {
            inputTokens.addAndGet(usage.inputTokenCount());
        }
        if (usage.outputTokenCount() != null) {
            outputTokens.addAndGet(usage.outputTokenCount());
        }
    }

    long inputTokens() {
        return inputTokens.get();
    }

    long outputTokens() {
        return outputTokens.get();
    }
}
