package com.wgh.rag.controller.support;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SSE 重连相关的接入层小工具。
 *
 * <p>SSE 断点续传使用标准 {@code Last-Event-ID} 请求头；部分客户端（如 fetch + ReadableStream）
 * 不便设置请求头时，也接受请求体中的 {@code lastEventId} 字段。</p>
 */
public final class SseReplaySupport {

    /** 标准 SSE 断点续传请求头 */
    public static final String HEADER_LAST_EVENT_ID = "Last-Event-ID";

    private SseReplaySupport() {
    }

    /**
     * 注册连接关闭标记：超时/错误/完成任一发生即置位，供重放跟随任务及时退出。
     */
    public static AtomicBoolean wireCloseFlag(SseEmitter emitter) {
        AtomicBoolean closed = new AtomicBoolean(false);
        emitter.onCompletion(() -> closed.set(true));
        emitter.onError(error -> closed.set(true));
        emitter.onTimeout(() -> closed.set(true));
        return closed;
    }
}
