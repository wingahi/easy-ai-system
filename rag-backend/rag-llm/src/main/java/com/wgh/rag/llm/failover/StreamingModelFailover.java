package com.wgh.rag.llm.failover;

import com.wgh.rag.llm.entity.LlmModel;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 流式模型故障转移驱动器 —— 普通 RAG / Agent / 工作流共用的切链实现。
 *
 * <p>语义：
 * <ul>
 *   <li>首内容前失败（异步 onError 或同步异常）→ 按链顺序切换下一个模型；</li>
 *   <li>已产出内容后失败 → 不切链、不回滚、不重发，交由调用方如实报错；</li>
 *   <li>全部候选失败 → 由调用方下发明确的业务错误；</li>
 *   <li>active + CAS 抢占，重复的 onError 不会触发多次切链。</li>
 * </ul>
 *
 * <p>与调用形态无关的部分（尝试状态、切链判定、错误文案）全部收敛在这里，
 * 调用方只实现 {@link Listener} 中的聊天/Agent/工作流专属行为。</p>
 */
@Slf4j
public final class StreamingModelFailover {

    /** 发起一次模型调用；同步异常等价于首内容前失败 */
    @FunctionalInterface
    public interface AttemptInvoker {
        void invoke(FailoverAttempt attempt, LlmModel model) throws Throwable;
    }

    /** 调用方专属行为（SSE 下发、落库、指标、可观测事件等） */
    public interface Listener {

        /** 已创建尝试：用于注册取消回调、重置本轮工具轮次等 */
        void onAttemptCreated(FailoverAttempt attempt);

        /** 即将发起该尝试的模型调用：记录日志、回填实际模型、下发 model_switch */
        void onAttemptStart(FailoverAttempt attempt);

        /** 首内容前失败且已确定继续切链 */
        void onPreContentFailure(FailoverAttempt attempt, Throwable error);

        /** 已产出内容后失败，不再切链 */
        void onPostContentFailure(FailoverAttempt attempt, Throwable error);

        /** 链上所有模型均失败 */
        void onAllAttemptsFailed(List<LlmModel> chain, Throwable lastError);

        /** 客户端是否已取消（断连 / 显式停止） */
        boolean isCancelled();
    }

    private final List<LlmModel> chain;
    private final Listener listener;
    private final AttemptInvoker invoker;

    public StreamingModelFailover(List<LlmModel> chain, Listener listener, AttemptInvoker invoker) {
        this.chain = chain;
        this.listener = listener;
        this.invoker = invoker;
    }

    /**
     * 按链顺序启动第 index 个模型。
     * 流式调用为异步，首内容前的失败经 onError 回调回到 {@link #handleFailure}，
     * 因此不能用 try/catch 判定异步失败（同步异常仍在此兜底）。
     */
    public void start(int index) {
        FailoverAttempt attempt = new FailoverAttempt(index, chain.size(), chain.get(index));
        listener.onAttemptCreated(attempt);
        if (listener.isCancelled()) {
            // 已断开：不再发起模型调用，直接完成停止落库
            attempt.deactivate();
            return;
        }
        try {
            listener.onAttemptStart(attempt);
            invoker.invoke(attempt, chain.get(index));
        } catch (Throwable t) {
            // 同步异常（模型构建失败、请求构建失败等）：等价于首内容前失败
            handleFailure(attempt, t);
        }
    }

    /**
     * 统一失败入口：区分「首内容前」与「已产出内容」两种语义。
     */
    public void handleFailure(FailoverAttempt attempt, Throwable error) {
        if (!attempt.isActive()) {
            log.debug("忽略已失效模型尝试的失败回调: attempt={}/{}", attempt.index() + 1, attempt.total());
            return;
        }
        // 客户端主动断开：取消路径已落库，不切链、不下发错误事件
        if (listener.isCancelled()) {
            log.info("客户端已断开，忽略模型失败回调: attempt={}/{}", attempt.index() + 1, attempt.total());
            attempt.deactivate();
            return;
        }
        if (attempt.isStarted()) {
            // 已产出部分内容：不切换、不回滚、不重发，如实报错并结束
            attempt.deactivate();
            listener.onPostContentFailure(attempt, error);
            return;
        }
        // 首内容前失败：抢占切链权，防止重复回调造成重复切换
        if (!attempt.claimPreContentFailure()) {
            log.debug("忽略重复的首内容前失败回调: attempt={}/{}", attempt.index() + 1, attempt.total());
            return;
        }
        listener.onPreContentFailure(attempt, error);

        int nextIndex = attempt.index() + 1;
        if (nextIndex < chain.size()) {
            start(nextIndex);
            return;
        }
        listener.onAllAttemptsFailed(chain, error);
    }
}
