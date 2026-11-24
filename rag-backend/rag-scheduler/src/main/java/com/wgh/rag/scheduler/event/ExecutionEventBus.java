package com.wgh.rag.scheduler.event;

import com.wgh.rag.execution.event.ExecutionEvent;
import com.wgh.rag.execution.event.ExecutionEventSink;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单次执行的进程内事件总线。
 *
 * <p>职责：给事件分配执行内单调递增的 {@code seq}、保留最近一段事件用于断点重放、
 * 向订阅者分发。订阅与分发都在本实例内完成；跨实例重放需要事件表（S3），不在 P3 范围。
 *
 * <p>发布与订阅重放都在同一把锁内完成，保证同一执行的事件顺序与 seq 一致。
 */
@Slf4j
public class ExecutionEventBus {

    /** 环形缓冲保留的最大事件数。 */
    private static final int HISTORY_LIMIT = 500;

    private final String executionId;

    private final AtomicLong sequence = new AtomicLong();

    private final Deque<ExecutionEvent> history = new ArrayDeque<>(HISTORY_LIMIT);

    private final CopyOnWriteArrayList<ExecutionEventSink> subscribers = new CopyOnWriteArrayList<>();

    private volatile boolean closed;

    public ExecutionEventBus(String executionId) {
        this.executionId = executionId;
    }

    /** 执行标识。 */
    public String executionId() {
        return executionId;
    }

    /**
     * 发布事件。
     *
     * @param type    事件类型
     * @param payload 事件负载，不能包含 {@code null} 值
     * @return 已分配序号的事件；总线已关闭时返回 {@code null}
     */
    public synchronized ExecutionEvent publish(String type, Map<String, Object> payload) {
        if (closed) {
            return null;
        }
        ExecutionEvent event = new ExecutionEvent(executionId, sequence.incrementAndGet(), type,
                payload == null ? Map.of() : payload, Instant.now());
        history.addLast(event);
        while (history.size() > HISTORY_LIMIT) {
            history.removeFirst();
        }
        List<ExecutionEventSink> broken = new ArrayList<>();
        for (ExecutionEventSink subscriber : subscribers) {
            try {
                subscriber.emit(event);
            } catch (RuntimeException e) {
                broken.add(subscriber);
                log.warn("执行事件订阅者写出失败，已移除 executionId={} type={} error={}",
                        executionId, type, e.getMessage());
            }
        }
        subscribers.removeAll(broken);
        return event;
    }

    /**
     * 订阅事件。
     *
     * @param fromSeq 只重放 seq 大于该值的事件；传 0 表示从头重放全部历史
     * @param sink    事件出口
     * @return 取消订阅句柄
     */
    public AutoCloseable attach(long fromSeq, ExecutionEventSink sink) {
        List<ExecutionEvent> replay;
        synchronized (this) {
            replay = history.stream().filter(event -> event.seq() > fromSeq).toList();
            if (!closed) {
                subscribers.add(sink);
            }
        }
        replay.forEach(sink::emit);
        return () -> subscribers.remove(sink);
    }

    /** 关闭总线：向订阅者发送关闭信号，之后不再接受发布。 */
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (ExecutionEventSink subscriber : subscribers) {
            try {
                subscriber.close();
            } catch (RuntimeException e) {
                log.debug("关闭执行事件订阅者失败 executionId={} error={}", executionId, e.getMessage());
            }
        }
        subscribers.clear();
    }

    /** 当前事件序号。 */
    public long currentSeq() {
        return sequence.get();
    }

    /** 是否已关闭。 */
    public boolean isClosed() {
        return closed;
    }
}
