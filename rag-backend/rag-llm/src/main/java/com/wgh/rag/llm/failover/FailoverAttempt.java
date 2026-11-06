package com.wgh.rag.llm.failover;

import com.wgh.rag.llm.entity.LlmModel;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单次模型尝试的共享状态。
 *
 * <p>started=true 表示本次尝试已经产出可见内容（流式首 token / 同步首个响应 / 工具已执行）：
 * 此后失败不再切链，避免已输出内容被回滚重发、或工具调用被重复执行。</p>
 *
 * <p>流式与同步两种调用形态共用本状态机，保证切链判定完全一致。</p>
 */
public final class FailoverAttempt {

    private final int index;
    private final int total;
    private final LlmModel model;
    /** 本次尝试的独立输出缓冲：切链时不会污染下一个模型的输出 */
    private final StringBuilder content = new StringBuilder();
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean active = new AtomicBoolean(true);

    public FailoverAttempt(int index, int total, LlmModel model) {
        this.index = index;
        this.total = total;
        this.model = model;
    }

    public int index() {
        return index;
    }

    public int total() {
        return total;
    }

    public LlmModel model() {
        return model;
    }

    /** 收到首个片段/首个响应/已执行工具：返回 false 表示该尝试已失效，回调应被忽略 */
    public boolean markStarted() {
        if (!active.get()) return false;
        started.set(true);
        return true;
    }

    public boolean isStarted() {
        return started.get();
    }

    public boolean isActive() {
        return active.get();
    }

    public void deactivate() {
        active.set(false);
    }

    /** 追加流式片段（与取消快照互斥，避免停顿时读到半截文本） */
    public void appendPartial(String text) {
        synchronized (content) {
            content.append(text);
        }
    }

    /** 取消时的内容快照 */
    public String snapshotContent() {
        synchronized (content) {
            return content.toString();
        }
    }

    /** 抢占首内容前失败的切链权，避免 onError 重复回调触发多次切链 */
    public boolean claimPreContentFailure() {
        return active.compareAndSet(true, false);
    }

    /** 兼容旧命名（首 token 前失败） */
    public boolean claimPreTokenFailure() {
        return claimPreContentFailure();
    }
}
