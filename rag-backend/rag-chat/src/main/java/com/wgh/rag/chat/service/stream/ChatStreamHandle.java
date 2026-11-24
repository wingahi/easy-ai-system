package com.wgh.rag.chat.service.stream;

import com.wgh.rag.chat.service.stream.replay.ClientDetachAware;
import lombok.extern.slf4j.Slf4j;

/**
 * 一次流式对话的生命周期句柄。
 *
 * <p>传输层（SSE）连接超时/错误/完成时由接入层调用对应回调。启用可重放出口时
 * （{@code detachAware} 非空），客户端意外消失（刷新/卸载/网络断开）<b>不取消生成</b>：
 * 只停止向下游推送，执行保持原状态，交给重连续看、续跑或最终持久化收口；
 * 用户主动停止仍由 {@code ChatCancellationStore} 的 explicit 标记驱动并落 STOPPED。</p>
 *
 * <p>未启用可重放出口（总开关关闭）时保持原语义：连接断开即取消并落 STOPPED。</p>
 */
@Slf4j
public final class ChatStreamHandle {

    private final ChatStreamCancellation cancellation;
    private final Long sessionId;
    private final ClientDetachAware detachAware;

    public ChatStreamHandle(ChatStreamCancellation cancellation, Long sessionId) {
        this(cancellation, sessionId, null);
    }

    public ChatStreamHandle(ChatStreamCancellation cancellation, Long sessionId,
                            ClientDetachAware detachAware) {
        this.cancellation = cancellation;
        this.sessionId = sessionId;
        this.detachAware = detachAware;
    }

    /** 连接超时。 */
    public void onTimeout() {
        log.warn("SSE连接超时: session={}", sessionId);
        detach();
    }

    /** 连接错误。 */
    public void onError(Throwable error) {
        log.warn("SSE连接错误: session={}, reason={}", sessionId,
                error == null ? null : error.getMessage());
        detach();
    }

    /** 连接完成（含客户端提前断开）。 */
    public void onCompletion() {
        log.info("SSE连接完成: session={}", sessionId);
        // 客户端提前断开时 Spring 只会触发 completion：
        // 可重放出口下仅停止推送、保持执行状态；否则兜底取消（已正常终态则落库权已被抢占）
        detach();
    }

    private void detach() {
        if (detachAware != null) {
            detachAware.onClientDetached(cancellation::cancel);
        } else {
            cancellation.cancel();
        }
    }
}
