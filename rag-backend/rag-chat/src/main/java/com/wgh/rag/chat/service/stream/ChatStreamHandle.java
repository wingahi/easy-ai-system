package com.wgh.rag.chat.service.stream;

import lombok.extern.slf4j.Slf4j;

/**
 * 一次流式对话的生命周期句柄。
 *
 * <p>传输层（SSE）连接超时/错误/完成时由接入层调用对应回调，语义与原先在
 * {@code RagChatService#streamChat} 内注册的回调完全一致：置位取消并落库为 STOPPED。</p>
 */
@Slf4j
public final class ChatStreamHandle {

    private final ChatStreamCancellation cancellation;
    private final Long sessionId;

    public ChatStreamHandle(ChatStreamCancellation cancellation, Long sessionId) {
        this.cancellation = cancellation;
        this.sessionId = sessionId;
    }

    /** 连接超时。 */
    public void onTimeout() {
        log.warn("SSE连接超时，取消生成: session={}", sessionId);
        cancellation.cancel();
    }

    /** 连接错误。 */
    public void onError(Throwable error) {
        log.warn("SSE连接错误，取消生成: session={}, reason={}", sessionId,
                error == null ? null : error.getMessage());
        cancellation.cancel();
    }

    /** 连接完成（含客户端提前断开）。 */
    public void onCompletion() {
        log.info("SSE连接完成: session={}", sessionId);
        // 客户端提前断开时 Spring 只会触发 completion，这里兜底取消（已正常终态则落库权已被抢占）
        cancellation.cancel();
    }
}
