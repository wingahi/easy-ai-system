package com.wgh.rag.llm.failover;

import com.wgh.rag.llm.entity.LlmModel;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 同步模型故障转移适配层 —— 供 Agent（Planner / Reflection / Step）与工作流 LLM 节点使用。
 *
 * <p>与流式版本的差异仅在于「一次尝试如何驱动」：这里由调用方同步执行完一整次逻辑调用
 * （单次 chat 或多轮工具循环）。切链判定复用同一个 {@link FailoverAttempt}：
 * <ul>
 *   <li>调用方在收到首个模型响应后调用 {@code attempt.markStarted()}；</li>
 *   <li>此后（例如后续工具轮次）再失败 → 不切链，直接抛错，避免工具副作用重复执行；</li>
 *   <li>首个响应前失败 → 切下一个模型；全部失败 → 抛出明确的业务错误。</li>
 * </ul>
 */
@Slf4j
public final class BlockingModelFailover {

    /** 调用方专属行为 */
    public interface Listener {

        /** 已创建尝试（可重置本轮状态） */
        default void onAttemptCreated(FailoverAttempt attempt) {
        }

        /** 即将发起该尝试的模型调用（记录日志 / 下发 model_switch） */
        default void onAttemptStart(FailoverAttempt attempt) {
        }

        /** 首内容前失败且已确定继续切链 */
        default void onPreContentFailure(FailoverAttempt attempt, Throwable error) {
        }

        /** 已产出内容后失败，不再切链 */
        default void onPostContentFailure(FailoverAttempt attempt, Throwable error) {
        }

        /** 链上所有模型均失败 */
        default void onAllAttemptsFailed(List<LlmModel> chain, Throwable lastError) {
        }
    }

    @FunctionalInterface
    public interface AttemptInvoker<T> {
        T invoke(FailoverAttempt attempt, LlmModel model) throws Throwable;
    }

    private BlockingModelFailover() {
    }

    /**
     * 按链顺序执行，返回首个成功结果；全部失败抛出 {@link ModelFailoverException}。
     */
    public static <T> T call(List<LlmModel> chain, AttemptInvoker<T> invoker, Listener listener) {
        Throwable lastError = null;
        for (int i = 0; i < chain.size(); i++) {
            LlmModel candidate = chain.get(i);
            FailoverAttempt attempt = new FailoverAttempt(i, chain.size(), candidate);
            listener.onAttemptCreated(attempt);
            try {
                listener.onAttemptStart(attempt);
                return invoker.invoke(attempt, candidate);
            } catch (Throwable error) {
                lastError = error;
                if (attempt.isStarted()) {
                    // 已产出内容（含已执行工具）后失败：不切链，避免重复副作用
                    attempt.deactivate();
                    listener.onPostContentFailure(attempt, error);
                    log.warn("[Failover] 模型 {} (id={}) 已产出内容后失败（尝试 {}/{}）：{}",
                            candidate.getModelName(), candidate.getId(), i + 1, chain.size(),
                            FailoverErrors.describe(error));
                    throw new ModelFailoverException("模型响应中途失败（不切换备用模型）: "
                            + FailoverErrors.userFacing(error), error);
                }
                if (!attempt.claimPreContentFailure()) {
                    log.debug("忽略已失效模型尝试的失败: attempt={}/{}", i + 1, chain.size());
                    throw new ModelFailoverException(FailoverErrors.userFacing(error), error);
                }
                listener.onPreContentFailure(attempt, error);
                log.warn("[Failover] 模型 {} (id={}) 首内容前失败（尝试 {}/{}）：{}",
                        candidate.getModelName(), candidate.getId(), i + 1, chain.size(),
                        FailoverErrors.describe(error));
            }
        }
        listener.onAllAttemptsFailed(chain, lastError);
        // 单模型时保留原始错误文案（与原调用方行为一致）；多模型才给出聚合的明确业务错误
        String message = chain.size() == 1
                ? FailoverErrors.userFacing(lastError)
                : FailoverErrors.allModelsFailed(chain.size(), lastError);
        log.error("[Failover] {}", message);
        throw new ModelFailoverException(message, lastError);
    }
}
