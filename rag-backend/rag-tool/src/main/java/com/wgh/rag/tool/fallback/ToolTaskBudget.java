package com.wgh.rag.tool.fallback;

import java.util.function.LongSupplier;

/**
 * 单任务工具调用预算（请求数 + 总时长）。
 *
 * <p>任务边界：一次用户聊天请求 / 一个 Agent 步骤 / 一次工作流运行。
 * 超预算时直接返回结构化错误，避免长任务无限重试拖垮整个请求。
 */
public final class ToolTaskBudget {

    private final int maxRequests;
    private final long maxTotalMs;
    private final LongSupplier clock;
    private final long startedAt;
    private int used;

    public ToolTaskBudget(int maxRequests, long maxTotalMs) {
        this(maxRequests, maxTotalMs, System::currentTimeMillis);
    }

    ToolTaskBudget(int maxRequests, long maxTotalMs, LongSupplier clock) {
        this.maxRequests = maxRequests;
        this.maxTotalMs = maxTotalMs;
        this.clock = clock;
        this.startedAt = clock.getAsLong();
    }

    public static ToolTaskBudget of(ToolFallbackProperties properties) {
        return new ToolTaskBudget(properties.getTaskMaxRequests(), properties.getTaskMaxTotalMs());
    }

    /** 申请一次工具调用配额；预算不足返回 false。 */
    public synchronized boolean tryAcquire() {
        if (used >= maxRequests) {
            return false;
        }
        if (remainingMs() <= 0) {
            return false;
        }
        used++;
        return true;
    }

    /** 剩余总时长预算（毫秒），可能为 0。 */
    public synchronized long remainingMs() {
        long elapsed = clock.getAsLong() - startedAt;
        return Math.max(maxTotalMs - elapsed, 0L);
    }

    public synchronized int used() {
        return used;
    }
}
