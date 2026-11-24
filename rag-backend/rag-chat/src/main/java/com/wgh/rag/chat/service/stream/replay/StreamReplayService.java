package com.wgh.rag.chat.service.stream.replay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.metrics.RagMetrics;
import com.wgh.rag.common.result.ResultCode;
import com.wgh.rag.execution.event.ExecutionStreamSink;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SSE 事件重放的编排入口：创建可重放流、按 {@code Last-Event-ID} 重连接管。
 *
 * <p>重连语义：</p>
 * <ol>
 *   <li>校验 {@code Last-Event-ID} 指向的流存在、类别与所属对象匹配、且仍是该对象的当前流
 *       （新一次生成会覆盖当前流指针，旧流不可再接管）；</li>
 *   <li>先回放 {@code seq > lastEventId.seq} 的缓冲事件（保持原事件名、负载与 id）；</li>
 *   <li>随后按 {@code pollInterval} 轮询共享缓冲持续跟随，直到流终态、空闲超时或跟随超时；</li>
 *   <li>无法完整收口时下发 {@code stream_replay_incomplete}，缓冲缺口下发 {@code stream_replay_gap}，
 *       由客户端回退到会话历史/运行状态接口，避免静默丢一段。</li>
 * </ol>
 *
 * <p>重连不重新生成、不重复扣费、不重复写入用户消息。客户端断开只停止推送，
 * 生成继续推进（断连不等于执行失败），由重连接管或最终持久化收口。</p>
 */
@Slf4j
@Service
public class StreamReplayService {

    /** 流类别：对话流 */
    public static final String KIND_CHAT = "chat";
    /** 流类别：聊天窗口绑定的工作流 HITL 流 */
    public static final String KIND_WORKFLOW_HITL = "workflow-hitl";

    /** 缓冲缺口（lastEventId 早于最老可用事件） */
    public static final String EVENT_REPLAY_GAP = "stream_replay_gap";
    /** 重放未能完整收口（生成实例消失/缓冲降级/跟随超时） */
    public static final String EVENT_REPLAY_INCOMPLETE = "stream_replay_incomplete";

    private final StreamReplayProperties properties;
    private final StreamReplayBuffer buffer;
    private final RagMetrics metrics;
    private final ObjectMapper objectMapper;

    /** 周期刷盘：单线程足够，仅遍历活跃流并做批量写入 */
    private final ScheduledExecutorService flushScheduler =
            Executors.newSingleThreadScheduledExecutor(daemonFactory("stream-replay-flush"));
    /** 重连跟随：每个连接一个常驻轮询任务，用缓存线程池承载 */
    private final ExecutorService followerExecutor =
            Executors.newCachedThreadPool(daemonFactory("stream-replay-follow"));

    private final Map<String, ReplayableStreamSink> active = new ConcurrentHashMap<>();
    private volatile boolean disabledWarned;

    public StreamReplayService(StreamReplayProperties properties, StreamReplayBuffer buffer,
                               RagMetrics metrics, ObjectMapper objectMapper) {
        this.properties = properties;
        this.buffer = buffer;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
        flushScheduler.scheduleAtFixedRate(this::flushTick, 100, 100, TimeUnit.MILLISECONDS);
    }

    /**
     * 打开一条可重放的对话流。
     *
     * @param sessionId  会话 id（重连时作为所属对象校验）
     * @param downstream 下游事件出口（SSE 适配器）
     * @return 实际使用的出口；未启用重放时 {@code sink} 即 {@code downstream}，{@code detachAware} 为 null
     */
    public ReplayStream openChatStream(Long sessionId, ExecutionStreamSink downstream) {
        ExecutionStreamSink sink = open(KIND_CHAT, sessionId, downstream);
        return sink == downstream
                ? new ReplayStream(downstream, null)
                : new ReplayStream(sink, (ClientDetachAware) sink);
    }

    /**
     * 打开一条可重放的通用流（如聊天窗口绑定的工作流 HITL 输出）。
     *
     * <p>只要总开关开启就包装出口：即使 Redis 不可用，也保证「客户端断开不等于执行失败」，
     * 只是无法跨副本重放。总开关显式关闭时才退回原语义。</p>
     *
     * @param kind      流类别（同时作为指标的有限标签）
     * @param subjectId 所属对象（会话 id / 运行 id）
     * @param downstream 下游事件出口
     */
    public ExecutionStreamSink open(String kind, Long subjectId, ExecutionStreamSink downstream) {
        if (!properties.isEnabled()) {
            metrics.recordStreamReplayFallback(kind, "disabled");
            return downstream;
        }
        if (!buffer.available()) {
            warnDisabledOnce(kind);
        }
        return new ReplayableStreamSink(newStreamId(kind), kind, subjectId,
                downstream, this, buffer, properties, metrics, objectMapper);
    }

    /**
     * 按 {@code Last-Event-ID} 接管已有流：回放缺失事件后持续跟随，不重新触发生成。
     *
     * @param kind       期望的流类别
     * @param subjectId  期望的所属对象
     * @param lastEventId 客户端最后一个事件 id（{@code <streamId>:<seq>}）
     * @param downstream 新的 SSE 事件出口
     * @param closed     新连接是否已关闭（断连即停止跟随）
     * @throws BusinessException 流不存在/已过期/类型或归属不匹配/已被新生成取代
     */
    public void resume(String kind, Long subjectId, String lastEventId,
                       ExecutionStreamSink downstream, AtomicBoolean closed) {
        if (!properties.isEnabled()) {
            metrics.recordStreamReplayFallback(kind, "disabled");
            throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND,
                    "事件重放未启用，无法按 Last-Event-ID 续传，请重新加载会话历史");
        }
        if (!buffer.available()) {
            metrics.recordStreamReplayFallback(kind, "disabled");
            throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND,
                    "事件重放暂不可用（共享存储未连接），请重新加载会话历史");
        }
        StreamRef ref = parse(lastEventId);
        if (ref == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "Last-Event-ID 格式不合法");
        }
        StreamReplayBuffer.StreamMeta meta;
        try {
            meta = buffer.readMeta(ref.streamId());
        } catch (RuntimeException e) {
            metrics.recordStreamReplayFallback(kind, "error");
            log.warn("读取事件流元信息失败，拒绝重连: stream={}, reason={}", ref.streamId(), e.getMessage());
            throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND,
                    "事件重放暂不可用（共享存储异常），请重新加载会话历史");
        }
        if (meta == null) {
            throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND,
                    "事件流已过期或不存在，请重新加载会话历史");
        }
        if (!kind.equals(meta.kind())) {
            throw new BusinessException(ResultCode.OPERATION_NOT_ALLOWED, "事件流类型与当前请求不匹配");
        }
        if (!Objects.equals(subjectId, meta.subjectId())) {
            throw new BusinessException(ResultCode.OPERATION_NOT_ALLOWED, "事件流不属于当前会话或运行");
        }
        try {
            if (!buffer.isCurrent(kind, subjectId, ref.streamId())) {
                throw new BusinessException(ResultCode.OPERATION_NOT_ALLOWED,
                        "该事件流已被新的生成取代，请重新加载会话历史");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            metrics.recordStreamReplayFallback(kind, "error");
            throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND,
                    "事件重放暂不可用（共享存储异常），请重新加载会话历史");
        }
        followerExecutor.execute(() -> follow(kind, ref.streamId(), ref.seq(), downstream, closed));
    }

    /** 从请求头/请求体解析 {@code Last-Event-ID}：请求头优先，便于标准 SSE 客户端直连。 */
    public static String resolveLastEventId(String header, Object bodyValue) {
        if (header != null && !header.isBlank()) {
            return header.trim();
        }
        if (bodyValue == null) {
            return null;
        }
        String value = String.valueOf(bodyValue).trim();
        return value.isEmpty() || "null".equals(value) ? null : value;
    }

    /** {@code Last-Event-ID} 拆解结果。 */
    public record StreamRef(String streamId, long seq) {
    }

    /** 可重放流：实际出口与断连感知回调。 */
    public record ReplayStream(ExecutionStreamSink sink, ClientDetachAware detachAware) {
    }

    // ==================== 内部实现 ====================

    void register(ReplayableStreamSink sink) {
        active.put(sink.streamId(), sink);
    }

    void unregister(ReplayableStreamSink sink) {
        active.remove(sink.streamId(), sink);
    }

    private void flushTick() {
        for (ReplayableStreamSink sink : active.values()) {
            try {
                sink.flushIfDue();
            } catch (Exception e) {
                log.debug("事件重放缓冲刷盘失败: stream={}, reason={}", sink.streamId(), e.getMessage());
            }
        }
    }

    private void follow(String kind, String streamId, long fromSeq, ExecutionStreamSink sink,
                        AtomicBoolean closed) {
        long cursor = fromSeq;
        long startedAt = System.currentTimeMillis();
        long lastActivityAt = startedAt;
        boolean firstRound = true;
        int batch = Math.max(properties.getPollBatchSize(), 1);
        long pollMillis = Math.max(properties.getPollInterval().toMillis(), 50L);

        log.info("SSE 事件重放开始: kind={}, stream={}, fromSeq={}", kind, streamId, cursor);
        try {
            while (!closed.get()) {
                List<String> entries = firstRound
                        ? buffer.readAll(streamId)
                        : buffer.readTail(streamId, batch);
                if (firstRound) {
                    firstRound = false;
                    reportGap(kind, sink, streamId, cursor, entries);
                }

                boolean progressed;
                do {
                    int emittedThisRound = 0;
                    for (String raw : entries) {
                        if (closed.get()) {
                            return;
                        }
                        BufferedEvent event = parseEvent(raw);
                        if (event == null || event.seq() <= cursor) {
                            continue;
                        }
                        sink.emit(event.type(), event.data(), streamId + ":" + event.seq());
                        cursor = event.seq();
                        emittedThisRound++;
                        lastActivityAt = System.currentTimeMillis();
                    }
                    progressed = entries.size() >= batch && emittedThisRound > 0;
                    if (progressed) {
                        entries = buffer.readTail(streamId, batch);
                    }
                } while (progressed);

                StreamReplayBuffer.StreamMeta meta = buffer.readMeta(streamId);
                if (meta == null) {
                    incomplete(kind, sink, streamId, cursor, "stream-expired");
                    return;
                }
                if (meta.degraded()) {
                    incomplete(kind, sink, streamId, cursor, "buffer-degraded");
                    return;
                }
                if (meta.terminal()) {
                    if (cursor >= meta.lastSeq()) {
                        sink.complete();
                        log.info("SSE 事件重放完成: stream={}, lastSeq={}", streamId, cursor);
                        return;
                    }
                    continue;
                }
                if (System.currentTimeMillis() - lastActivityAt >= properties.getIdleTimeout().toMillis()) {
                    incomplete(kind, sink, streamId, cursor, "idle-timeout");
                    return;
                }
                if (System.currentTimeMillis() - startedAt >= properties.getFollowTimeout().toMillis()) {
                    incomplete(kind, sink, streamId, cursor, "follow-timeout");
                    return;
                }
                Thread.sleep(pollMillis);
            }
        } catch (IOException | IllegalStateException e) {
            // 重连客户端再次断开：停止跟随即可，生成侧不受影响
            log.info("SSE 重连客户端已断开，停止跟随: stream={}, lastSeq={}", streamId, cursor);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("SSE 事件重放异常: stream={}, lastSeq={}, reason={}", streamId, cursor, e.getMessage());
            metrics.recordStreamReplayFallback(kind, "error");
            incomplete(kind, sink, streamId, cursor, "replay-error");
        }
    }

    private void reportGap(String kind, ExecutionStreamSink sink, String streamId, long cursor,
                           List<String> entries) throws IOException {
        Long oldest = firstSeq(entries);
        if (oldest == null || cursor + 1 >= oldest) {
            return;
        }
        metrics.recordStreamReplayEvent(kind, "gap", 1);
        log.warn("事件重放存在缺口（客户端游标早于缓冲最老事件）: stream={}, requestedFrom={}, oldestAvailable={}",
                streamId, cursor + 1, oldest);
        sink.emit(EVENT_REPLAY_GAP,
                Map.of("streamId", streamId, "requestedFrom", cursor + 1, "oldestAvailable", oldest),
                streamId + ":" + cursor);
    }

    private void incomplete(String kind, ExecutionStreamSink sink, String streamId, long cursor,
                            String reason) {
        metrics.recordStreamReplayEvent(kind, "incomplete", 1);
        log.warn("事件重放未能完整收口: stream={}, lastSeq={}, reason={}", streamId, cursor, reason);
        try {
            sink.emit(EVENT_REPLAY_INCOMPLETE,
                    Map.of("streamId", streamId, "lastSeq", cursor, "reason", reason),
                    streamId + ":" + cursor);
        } catch (IOException ignored) {
            // 客户端已断开
        }
        try {
            sink.complete();
        } catch (Exception ignored) {
            // 客户端已断开
        }
    }

    private BufferedEvent parseEvent(String raw) {
        try {
            JsonNode node = objectMapper.readTree(raw);
            long seq = node.path("seq").asLong();
            String type = node.hasNonNull("type") ? node.get("type").asText() : null;
            if (seq <= 0 || type == null || type.isBlank()) {
                return null;
            }
            JsonNode dataNode = node.get("data");
            Object data = dataNode == null || dataNode.isNull()
                    ? Map.of()
                    : objectMapper.convertValue(dataNode, Object.class);
            return new BufferedEvent(seq, type, data);
        } catch (Exception e) {
            log.debug("忽略无法解析的缓冲事件: {}", e.getMessage());
            return null;
        }
    }

    private static Long firstSeq(List<String> entries) {
        // 首个条目即最老事件；只解析 seq，避免整条反序列化
        for (String raw : entries) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            int idx = raw.indexOf("\"seq\":");
            if (idx < 0) {
                continue;
            }
            int start = idx + 6;
            int end = start;
            while (end < raw.length() && (Character.isDigit(raw.charAt(end)) || raw.charAt(end) == '-')) {
                end++;
            }
            try {
                return Long.parseLong(raw.substring(start, end));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static StreamRef parse(String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) {
            return null;
        }
        int idx = lastEventId.lastIndexOf(':');
        if (idx <= 0 || idx == lastEventId.length() - 1) {
            return null;
        }
        try {
            long seq = Long.parseLong(lastEventId.substring(idx + 1));
            if (seq < 0) {
                return null;
            }
            return new StreamRef(lastEventId.substring(0, idx), seq);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String newStreamId(String kind) {
        String prefix = KIND_WORKFLOW_HITL.equals(kind) ? "hitl-" : "chat-";
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }

    private void warnDisabledOnce(String kind) {
        if (!disabledWarned) {
            disabledWarned = true;
            metrics.recordStreamReplayFallback(kind, "disabled");
            log.warn("SSE 事件重放未启用（开关关闭或 Redis 不可用），多副本断线重连将无法补齐缺失事件");
        }
    }

    @PreDestroy
    void shutdown() {
        followerExecutor.shutdownNow();
        flushScheduler.shutdownNow();
    }

    private static ThreadFactory daemonFactory(String name) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, name + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    /** 缓冲事件反序列化结果。 */
    private record BufferedEvent(long seq, String type, Object data) {
    }
}
