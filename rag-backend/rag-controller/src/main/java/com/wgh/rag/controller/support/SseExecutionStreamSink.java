package com.wgh.rag.controller.support;

import com.wgh.rag.execution.event.ExecutionStreamSink;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

/**
 * {@link ExecutionStreamSink} 的 SSE 适配器（仅在接入层出现）。
 *
 * <p>把执行域下发的「事件名 + 负载」原样写到 {@link SseEmitter}，事件名与负载结构均不做转换，
 * 因此 SSE 对外协议与重构前完全一致。事件顺序由执行域串行调用保证。</p>
 */
public final class SseExecutionStreamSink implements ExecutionStreamSink {

    private final SseEmitter emitter;

    public SseExecutionStreamSink(SseEmitter emitter) {
        this.emitter = emitter;
    }

    @Override
    public void emit(String type, Object payload) throws IOException {
        emitter.send(SseEmitter.event().name(type).data(payload));
    }

    @Override
    public void complete() {
        emitter.complete();
    }

    @Override
    public void fail(Throwable error) {
        emitter.completeWithError(error);
    }
}
