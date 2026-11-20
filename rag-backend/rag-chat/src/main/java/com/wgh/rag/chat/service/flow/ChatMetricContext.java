package com.wgh.rag.chat.service.flow;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 单次对话的指标上下文（线程内传递，避免被并发请求串用）。
 *
 * <p>route 在链路分支处回填，firstToken 仅记录一次，用于区分冷启动与模型抖动。</p>
 */
public record ChatMetricContext(AtomicReference<String> route, AtomicBoolean firstToken, long startNanos) {
}
