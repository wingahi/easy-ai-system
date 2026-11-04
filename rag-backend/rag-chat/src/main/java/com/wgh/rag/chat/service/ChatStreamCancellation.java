package com.wgh.rag.chat.service;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单次对话生成的可取消句柄。
 *
 * <p>取消来源有两类：客户端断连（SSE 写失败 / onError / onTimeout）与显式取消。
 * 取消后所有流式回调通过 {@link #isCancelled()} 短路，不再继续消费 token；
 * 同时只允许一个终态落库（正常完成 / 用户停止 / 失败），避免重复写入。</p>
 */
@Slf4j
final class ChatStreamCancellation {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean finalized = new AtomicBoolean(false);
    private volatile Runnable cancelHook;

    boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * 注册取消终态回调（绑定当前模型尝试，用于持久化已生成的部分内容）。
     * 若注册前已取消，则立即执行一次。
     */
    void onCancel(Runnable hook) {
        this.cancelHook = hook;
        if (cancelled.get()) {
            runHookOnce();
        }
    }

    /**
     * 触发取消：置位后执行终态落库，并保证只执行一次。
     */
    void cancel() {
        cancelled.set(true);
        runHookOnce();
    }

    /**
     * 抢占唯一的终态落库权（正常完成 / 失败路径使用）。
     *
     * @return true 表示本次是首个终态，调用方可以落库；false 表示已由取消路径处理
     */
    boolean markFinalized() {
        return finalized.compareAndSet(false, true);
    }

    private void runHookOnce() {
        Runnable hook = cancelHook;
        if (hook == null) {
            return;
        }
        if (!finalized.compareAndSet(false, true)) {
            return;
        }
        try {
            hook.run();
        } catch (Exception e) {
            log.warn("取消终态落库失败: {}", e.getMessage());
        }
    }
}
