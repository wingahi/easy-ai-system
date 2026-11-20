package com.wgh.rag.chat.service.stream;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

/**
 * SSE 事件下发的统一出口。
 *
 * <p>把「事件名 + JSON 单行数据」的构造收敛到一处，并提供客户端断开时的静默容错；
 * 事件名常量仍由 {@link com.wgh.rag.chat.constant.ChatEvents} 定义，协议不变。</p>
 */
@Slf4j
public final class ChatSsePublisher {

    private ChatSsePublisher() {
    }

    /** 下发事件；写失败向上抛出，调用方据此决定是否取消生成。 */
    public static void send(SseEmitter emitter, String eventName, Object data) throws IOException {
        emitter.send(SseEmitter.event().name(eventName).data(data));
    }

    /** 尽力下发：客户端刷新/断开导致的失败只记日志，不影响已落库的业务结果。 */
    public static void sendQuietly(SseEmitter emitter, String eventName, Object data) {
        try {
            send(emitter, eventName, data);
        } catch (Exception e) {
            log.warn("SSE 下发失败（客户端可能已断开）: event={}, reason={}", eventName, e.getMessage());
        }
    }

    /** 安全关闭 SSE 流。 */
    public static void completeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception e) {
            log.warn("SSE 关闭失败: {}", e.getMessage());
        }
    }
}
