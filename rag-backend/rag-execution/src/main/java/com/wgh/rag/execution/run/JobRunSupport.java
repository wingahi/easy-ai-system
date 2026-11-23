package com.wgh.rag.execution.run;

import com.wgh.rag.execution.event.ExecutionEvent;
import com.wgh.rag.execution.job.JobContext;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/**
 * 把 {@link JobContext} 适配为执行域已有的事件出口与生命周期钩子。
 */
public final class JobRunSupport {

    private JobRunSupport() {
    }

    /** 执行域 {@code BiConsumer<String, Map>} 事件出口 → 作业事件出口。 */
    public static BiConsumer<String, Map<String, Object>> events(JobContext context) {
        AtomicLong seq = new AtomicLong();
        return (type, payload) -> context.events().emit(new ExecutionEvent(
                context.executionId(), seq.incrementAndGet(), type,
                payload == null ? Map.of() : payload, Instant.now()));
    }

    /** 作业心跳/取消 → 执行域生命周期钩子。 */
    public static RunLifecycle lifecycle(JobContext context) {
        return new RunLifecycle() {
            @Override
            public void heartbeat() {
                context.heartbeat();
            }

            @Override
            public void throwIfCancelled() {
                context.cancellation().throwIfCancellationRequested();
            }
        };
    }
}
