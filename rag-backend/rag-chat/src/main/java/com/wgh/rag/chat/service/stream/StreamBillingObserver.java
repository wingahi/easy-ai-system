package com.wgh.rag.chat.service.stream;

/**
 * 流式对话的计费观察者：由调用方按「是否真正下发过有效内容」做附加处理。
 *
 * <p>回调在生成线程执行，不会阻塞首包之外的路径；实现方需自行保证线程安全与异常隔离。</p>
 * <ul>
 *   <li>{@link #onOutputDelivered()}：首个有效内容增量已成功下发（本次对话计费生效）。</li>
 *   <li>{@link #onNoOutput()}：执行结束（含失败/停止/取消）时未下发任何有效内容（本次不计费）。</li>
 * </ul>
 */
public interface StreamBillingObserver {

    /** 无附加动作的默认实现。 */
    StreamBillingObserver NOOP = new StreamBillingObserver() {
    };

    default void onOutputDelivered() {
    }

    default void onNoOutput() {
    }
}
