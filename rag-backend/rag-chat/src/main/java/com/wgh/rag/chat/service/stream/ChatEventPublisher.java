package com.wgh.rag.chat.service.stream;

import com.wgh.rag.execution.event.ExecutionStreamSink;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

/**
 * 对话事件下发出口（传输无关）。
 *
 * <p>执行域只依赖 {@link ExecutionStreamSink}，不再感知 {@code SseEmitter}；事件名常量仍由
 * {@link com.wgh.rag.chat.constant.ChatEvents} 定义，协议不变。SSE 的具体写出由接入层适配器实现。</p>
 *
 * <p>写出失败（客户端断开）时 {@link #send(String, Object)} 抛出 {@link IOException}，
 * 调用方据此取消生成；{@link #sendQuietly(String, Object)} 只记日志，用于结果已落库后的尽力下发。</p>
 */
@Slf4j
public final class ChatEventPublisher {

    private final ExecutionStreamSink sink;

    public ChatEventPublisher(ExecutionStreamSink sink) {
        this.sink = sink;
    }

    /** 下发事件；写失败向上抛出，调用方据此决定是否取消生成。 */
    public void send(String eventName, Object data) throws IOException {
        sink.emit(eventName, data);
    }

    /** 尽力下发：客户端刷新/断开导致的失败只记日志，不影响已落库的业务结果。 */
    public void sendQuietly(String eventName, Object data) {
        try {
            send(eventName, data);
        } catch (Exception e) {
            log.warn("事件下发失败（客户端可能已断开）: event={}, reason={}", eventName, e.getMessage());
        }
    }

    /** 正常结束输出。 */
    public void complete() {
        sink.complete();
    }

    /** 安全结束输出。 */
    public void completeQuietly() {
        try {
            sink.complete();
        } catch (Exception e) {
            log.warn("事件流关闭失败: {}", e.getMessage());
        }
    }

    /** 异常结束输出。 */
    public void completeWithError(Throwable error) {
        sink.fail(error);
    }
}
