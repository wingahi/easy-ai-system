package com.wgh.rag.scheduler.event;

import com.wgh.rag.execution.event.ExecutionEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionEventBusTest {

    @Test
    void shouldAssignMonotonicSequence() {
        ExecutionEventBus bus = new ExecutionEventBus("e-1");

        bus.publish("execution.started", Map.of());
        bus.publish("execution.progress", Map.of("step", 1));

        assertThat(bus.currentSeq()).isEqualTo(2L);
    }

    @Test
    void shouldReplayEventsAfterFromSeq() throws Exception {
        ExecutionEventBus bus = new ExecutionEventBus("e-1");
        bus.publish("execution.started", Map.of());
        bus.publish("execution.progress", Map.of("step", 1));

        List<Long> received = new ArrayList<>();
        AutoCloseable subscription = bus.attach(1, event -> received.add(event.seq()));

        assertThat(received).containsExactly(2L);
        subscription.close();
    }

    @Test
    void shouldReplayFromBeginningWhenFromSeqIsZero() {
        ExecutionEventBus bus = new ExecutionEventBus("e-1");
        bus.publish("execution.started", Map.of());

        List<ExecutionEvent> received = new ArrayList<>();
        bus.attach(0, received::add);

        assertThat(received).hasSize(1);
        assertThat(received.get(0).type()).isEqualTo("execution.started");
        bus.publish("execution.completed", Map.of());
        assertThat(received).hasSize(2);
        assertThat(received.get(1).type()).isEqualTo("execution.completed");
    }

    @Test
    void shouldRejectPublishAfterClose() {
        ExecutionEventBus bus = new ExecutionEventBus("e-1");
        bus.close();

        assertThat(bus.publish("execution.failed", Map.of())).isNull();
    }
}
