package com.wgh.rag.chat.service.stream;

import com.wgh.rag.chat.service.ChatCancellationStore;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单次对话生成的可取消句柄。
 *
 * <p>取消来源：显式停止（用户点停止 / stop 接口），以及真实的生成异常。
 * 客户端刷新、卸载、网络断开属于传输层事件，由可重放出口吞掉并保持执行状态，
 * <b>不再</b>经由这里取消；只有总开关关闭时才回退为「断连即取消」。</p>
 *
 * <p>取消后所有流式回调通过 {@link #isCancelled()} 短路，不再继续消费 token；
 * 同时只允许一个终态落库（正常完成 / 用户停止 / 失败），避免重复写入。</p>
 *
 * <p>多实例：构造时传入会话级共享 key 与 {@link ChatCancellationStore} 后，
 * {@link #cancel(String)} 在来源为 {@code explicit} 时写入 Redis 标记；
 * {@link #isCancelled()} 按节流间隔轮询该标记并消费，从而让落在任意实例的
 * 显式停止请求生效。客户端断连/正常完成只置位本地标记，避免把“正常结束”
 * 写入共享存储而误伤同一会话的下一次生成。轮询默认 500ms，对 Redis 压力可忽略。</p>
 */
@Slf4j
public final class ChatStreamCancellation {

    /** 共享取消标记轮询间隔：兼顾取消延迟与 Redis 压力 */
    private static final long SHARED_POLL_INTERVAL_MILLIS = 500L;

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean finalized = new AtomicBoolean(false);
    private final String sharedKey;
    private final ChatCancellationStore sharedStore;

    private volatile Runnable cancelHook;
    private volatile long nextSharedCheckAt;

    public ChatStreamCancellation() {
        this(null, null);
    }

    public ChatStreamCancellation(String sharedKey, ChatCancellationStore sharedStore) {
        this.sharedKey = sharedKey;
        this.sharedStore = sharedStore;
        if (sharedStore != null && sharedKey != null) {
            // 新一轮生成开始：丢弃上一次遗留的停止标记，避免误伤本次生成
            sharedStore.clear(sharedKey);
        }
    }

    public boolean isCancelled() {
        if (cancelled.get()) {
            return true;
        }
        checkSharedCancel();
        return cancelled.get();
    }

    /**
     * 注册取消终态回调（绑定当前模型尝试，用于持久化已生成的部分内容）。
     * 若注册前已取消，则立即执行一次。
     */
    public void onCancel(Runnable hook) {
        this.cancelHook = hook;
        if (cancelled.get()) {
            runHookOnce();
        }
    }

    /**
     * 触发取消：置位后执行终态落库，并保证只执行一次。
     */
    public void cancel() {
        cancel("client-disconnect");
    }

    /**
     * 触发取消并记录来源（client-disconnect / explicit）。
     * <p>只有 {@code explicit} 才写入共享标记：正常完成/断连也会触发本地取消，
     * 若写入 Redis 会误伤同一会话的下一次生成。</p>
     */
    public void cancel(String source) {
        cancelled.set(true);
        if ("explicit".equals(source) && sharedStore != null && sharedKey != null) {
            boolean shared = sharedStore.cancel(sharedKey, source);
            if (!shared) {
                log.debug("取消标记未能写入 Redis，仅本实例生效: key={}", sharedKey);
            }
        }
        runHookOnce();
    }

    /**
     * 抢占唯一的终态落库权（正常完成 / 失败路径使用）。
     *
     * @return true 表示本次是首个终态，调用方可以落库；false 表示已由取消路径处理
     */
    public boolean markFinalized() {
        return finalized.compareAndSet(false, true);
    }

    private void checkSharedCancel() {
        if (sharedStore == null || sharedKey == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now < nextSharedCheckAt) {
            return;
        }
        nextSharedCheckAt = now + SHARED_POLL_INTERVAL_MILLIS;
        try {
            if (sharedStore.consume(sharedKey) && cancelled.compareAndSet(false, true)) {
                log.info("检测到跨实例取消标记，停止生成: key={}", sharedKey);
                runHookOnce();
            }
        } catch (Exception e) {
            // 取消标记读取失败不能影响生成主流程，下次轮询继续尝试
            log.debug("跨实例取消标记检查失败: key={}, reason={}", sharedKey, e.getMessage());
        }
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
