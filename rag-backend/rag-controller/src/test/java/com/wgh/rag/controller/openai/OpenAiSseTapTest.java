package com.wgh.rag.controller.openai;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 固定 {@link OpenAiSseTap} 对 Spring SseEmitter 内部契约的适配行为。
 */
class OpenAiSseTapTest {

    @Test
    void shouldForwardNamedEventsAndCompletion() throws Exception {
        SseEmitter emitter = new SseEmitter(10_000L);
        RecordingSink sink = new RecordingSink();

        OpenAiSseTap.attach(emitter, sink);
        emitter.send(SseEmitter.event().name("start").data(Map.of("sessionId", 7)));
        emitter.send(SseEmitter.event().name("content").data(Map.of("text", "你")));
        emitter.send(SseEmitter.event().name("content").data(Map.of("text", "好")));
        emitter.send(SseEmitter.event().name("done").data(Map.of("fullResponse", "你好")));
        emitter.complete();

        assertThat(sink.events).extracting(RecordedEvent::name)
                .containsExactly("start", "content", "content", "done");
        assertThat(sink.events.get(1).payload()).isEqualTo(Map.of("text", "你"));
        assertThat(sink.events.get(3).payload()).isEqualTo(Map.of("fullResponse", "你好"));
        assertThat(sink.completed).isTrue();
        assertThat(sink.error).isNull();
    }

    @Test
    void shouldReplayEventsSentBeforeAttach() throws Exception {
        SseEmitter emitter = new SseEmitter(10_000L);
        emitter.send(SseEmitter.event().name("content").data(Map.of("text", "早到")));

        RecordingSink sink = new RecordingSink();
        OpenAiSseTap.attach(emitter, sink);

        assertThat(sink.events).extracting(RecordedEvent::name).containsExactly("content");
        assertThat(sink.events.get(0).payload()).isEqualTo(Map.of("text", "早到"));
    }

    @Test
    void shouldForwardCompletionError() {
        SseEmitter emitter = new SseEmitter(10_000L);
        RecordingSink sink = new RecordingSink();

        OpenAiSseTap.attach(emitter, sink);
        emitter.completeWithError(new IllegalStateException("boom"));

        assertThat(sink.error).isInstanceOf(IllegalStateException.class);
        assertThat(sink.completed).isFalse();
    }

    private static final class RecordingSink implements OpenAiSseTap.EventSink {

        private final List<RecordedEvent> events = new ArrayList<>();
        private boolean completed;
        private Throwable error;

        @Override
        public void event(String name, Object payload) {
            events.add(new RecordedEvent(name, payload));
        }

        @Override
        public void completed() {
            completed = true;
        }

        @Override
        public void failed(Throwable error) {
            this.error = error;
        }
    }

    private record RecordedEvent(String name, Object payload) {
    }
}
