package com.wgh.rag.chat.service.stream.replay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.common.metrics.RagMetrics;
import com.wgh.rag.execution.event.ExecutionStreamSink;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 带序号与共享缓冲的流式出口：包装下游 {@link ExecutionStreamSink}。
 *
 * <p>职责：</p>
 * <ol>
 *   <li>为每个事件分配流内单调递增序号，生成 {@code <streamId>:<seq>} 形式的事件 id；</li>
 *   <li>按批次把事件写入 Redis 缓冲（{@code flushInterval}），降低每 token 一次往返的写放大；</li>
 *   <li>下游（SSE 连接）写失败时<b>不再向上抛出</b>，只停止推送并继续生成/写缓冲——
 *       客户端刷新、卸载、网络断开都不等于执行失败，执行状态保持不变，
 *       交给重连接管、续跑或最终持久化收口；</li>
 *   <li>用户主动停止仍走 {@code ChatCancellationStore} 的 explicit 标记，落 STOPPED。</li>
 * </ol>
 *
 * <p>Redis 未启用/故障时仍保持上述「断连不等于失败」语义，只是无法跨副本重放；
 * 降级会记录指标与 WARN，不静默丢弃可观测性。</p>
 */
@Slf4j
final class ReplayableStreamSink implements ExecutionStreamSink, ClientDetachAware {

    private static final long WARN_INTERVAL_MILLIS = 30_000L;

    private final String streamId;
    private final String kind;
    private final Long subjectId;
    private final ExecutionStreamSink downstream;
    private final StreamReplayService service;
    private final StreamReplayBuffer buffer;
    private final StreamReplayProperties properties;
    private final RagMetrics metrics;
    private final ObjectMapper objectMapper;

    private final Object lock = new Object();
    private final List<String> pending = new ArrayList<>();

    private long seq;
    private boolean initialized;
    private boolean degraded;
    private long lastFlushAt;
    private long lastEmitAt;
    private long lastWarnAt;

    private volatile boolean terminal;
    private volatile boolean downstreamDetached;

    ReplayableStreamSink(String streamId, String kind, Long subjectId,
                         ExecutionStreamSink downstream, StreamReplayService service,
                         StreamReplayBuffer buffer, StreamReplayProperties properties,
                         RagMetrics metrics, ObjectMapper objectMapper) {
        this.streamId = streamId;
        this.kind = kind;
        this.subjectId = subjectId;
        this.downstream = downstream;
        this.service = service;
        this.buffer = buffer;
        this.properties = properties;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
    }

    String streamId() {
        return streamId;
    }

    @Override
    public void emit(String type, Object payload) throws IOException {
        boolean buffering = buffer.available();
        long eventSeq;
        synchronized (lock) {
            if (terminal) {
                return;
            }
            eventSeq = ++seq;
            lastEmitAt = System.currentTimeMillis();
            if (buffering) {
                enqueueLocked(eventSeq, type, payload);
                if (!initialized || System.currentTimeMillis() - lastFlushAt >= flushIntervalMillis()) {
                    flushLocked();
                }
            }
        }
        if (downstreamDetached) {
            // 已确认下游断开：只写缓冲，等待重连接管/最终持久化
            return;
        }
        try {
            downstream.emit(type, payload, streamId + ":" + eventSeq);
        } catch (IOException | IllegalStateException e) {
            // 传输层断开不是执行失败：停止推送，生成继续；由重连/续跑或最终落库收口
            markDetached(eventSeq);
        }
    }

    /** 周期刷盘入口，由 {@link StreamReplayService} 的调度器调用。 */
    void flushIfDue() {
        synchronized (lock) {
            if (terminal) {
                return;
            }
            // 兜底清理：显式停止等路径不会回调 complete/fail，长时间无事件的流从活跃表移除
            if (lastEmitAt > 0 && System.currentTimeMillis() - lastEmitAt > properties.getTtl().toMillis()) {
                terminal = true;
                service.unregister(this);
                return;
            }
            if (pending.isEmpty()) {
                return;
            }
            if (!initialized || System.currentTimeMillis() - lastFlushAt >= flushIntervalMillis()) {
                flushLocked();
            }
        }
    }

    @Override
    public void complete() {
        finish(StreamReplayBuffer.STATUS_COMPLETED, null);
    }

    @Override
    public void fail(Throwable error) {
        finish(StreamReplayBuffer.STATUS_FAILED, error);
    }

    @Override
    public void onClientDetached(Runnable cancelAction) {
        // 意外断开（刷新/卸载/网络）不取消生成：执行保持原状态，等待重连接管或续跑。
        // 显式停止不经过这里，而是由 ChatCancellationStore 的 explicit 标记驱动。
        markDetached(seq);
    }

    // ==================== 内部实现 ====================

    private void finish(String status, Throwable error) {
        long lastSeq;
        synchronized (lock) {
            if (terminal) {
                return;
            }
            flushLocked();
            terminal = true;
            lastSeq = seq;
            if (initialized) {
                try {
                    buffer.markTerminal(streamId, kind, subjectId, status, lastSeq);
                } catch (RuntimeException e) {
                    log.debug("写入流终态失败: stream={}, reason={}", streamId, e.getMessage());
                }
                service.unregister(this);
            }
        }
        try {
            if (StreamReplayBuffer.STATUS_FAILED.equals(status)) {
                downstream.fail(error);
            } else {
                downstream.complete();
            }
        } catch (Exception e) {
            // 客户端已断开：终态已落库，忽略写出失败
            log.debug("流终态写出失败（客户端可能已断开）: stream={}, reason={}", streamId, e.getMessage());
        }
    }

    private void enqueueLocked(long eventSeq, String type, Object payload) {
        pending.add(toJson(eventSeq, type, payload));
        int max = Math.max(properties.getMaxEventsPerStream(), 1);
        if (pending.size() > max) {
            // 缓冲写入长期失败时避免内存无限增长：只保留最近的窗口
            pending.subList(0, pending.size() - max).clear();
        }
    }

    private void flushLocked() {
        if (pending.isEmpty()) {
            return;
        }
        if (!initialized) {
            try {
                buffer.initialize(streamId, kind, subjectId);
                initialized = true;
                service.register(this);
            } catch (RuntimeException e) {
                markDegraded("初始化", e);
                pending.clear();
                return;
            }
        }
        List<String> batch = new ArrayList<>(pending);
        try {
            buffer.append(streamId, batch);
            pending.clear();
            lastFlushAt = System.currentTimeMillis();
            metrics.recordStreamReplayEvent(kind, "append", batch.size());
        } catch (RuntimeException e) {
            markDegraded("写入", e);
            pending.clear();
        }
    }

    private void markDegraded(String stage, RuntimeException error) {
        if (!degraded) {
            degraded = true;
            metrics.recordStreamReplayFallback(kind, "error");
        }
        long now = System.currentTimeMillis();
        if (now - lastWarnAt >= WARN_INTERVAL_MILLIS) {
            lastWarnAt = now;
            log.warn("事件重放缓冲{}失败，本次流后续可能无法完整重放: stream={}, seq={}, reason={}",
                    stage, streamId, seq, error.getMessage());
        }
        try {
            buffer.markDegraded(streamId, kind, subjectId, seq);
        } catch (RuntimeException ignored) {
            // Redis 已不可用：降级状态本身也无法持久化，重连侧会以 idle/incomplete 收口
        }
    }

    private String toJson(long eventSeq, String type, Object payload) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("seq", eventSeq);
        entry.put("type", type);
        entry.put("data", payload);
        try {
            return objectMapper.writeValueAsString(entry);
        } catch (Exception e) {
            // 负载不可序列化时保留事件占位，保证序号连续与事件类型可重放
            log.debug("事件负载序列化失败，写入空负载占位: stream={}, type={}, reason={}",
                    streamId, type, e.getMessage());
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("seq", eventSeq);
            fallback.put("type", type);
            fallback.put("data", null);
            try {
                return objectMapper.writeValueAsString(fallback);
            } catch (Exception ignored) {
                return "{\"seq\":" + eventSeq + ",\"type\":\"message\",\"data\":null}";
            }
        }
    }

    private long flushIntervalMillis() {
        return Math.max(properties.getFlushInterval().toMillis(), 0L);
    }

    /** 标记下游断开：并发路径（写失败 / 生命周期回调）只记录一次。 */
    private void markDetached(long atSeq) {
        synchronized (lock) {
            if (terminal || downstreamDetached) {
                return;
            }
            downstreamDetached = true;
        }
        metrics.recordStreamReplayEvent(kind, "detached", 1);
        log.info("SSE 客户端已断开，停止推送并继续生成（执行状态保持不变）: stream={}, seq={}",
                streamId, atSeq);
    }
}
