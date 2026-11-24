package com.wgh.rag.scheduler.event;

import com.wgh.rag.execution.event.ExecutionEvent;
import com.wgh.rag.execution.event.ExecutionEventSink;
import com.wgh.rag.scheduler.entity.ExecutionEventRecord;
import com.wgh.rag.scheduler.config.SchedulerProperties;
import com.wgh.rag.scheduler.job.JobPayloadCodec;
import com.wgh.rag.scheduler.mapper.ExecutionEventMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 执行事件持久化与重放。
 *
 * <p>作为事件总线的订阅者把每次发布按 {@code (execution_id, seq)} 落库，供跨实例/断线重连按
 * {@code Last-Event-ID} 补齐。写入是尽力而为：失败只告警，不影响执行本身。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "wgh.scheduler", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ExecutionEventStore {

    private static final int PURGE_BATCH_LIMIT = 1000;

    private final ExecutionEventMapper mapper;

    private final JobPayloadCodec codec;

    private final SchedulerProperties properties;

    private final ScheduledExecutorService purgeLoop = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "sched-event-purge");
        thread.setDaemon(true);
        return thread;
    });

    public ExecutionEventStore(ExecutionEventMapper mapper, JobPayloadCodec codec,
                               SchedulerProperties properties) {
        this.mapper = mapper;
        this.codec = codec;
        this.properties = properties;
    }

    @PostConstruct
    public void start() {
        long intervalMillis = 3_600_000L;
        purgeLoop.scheduleWithFixedDelay(this::purgeSafely, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void stop() {
        purgeLoop.shutdownNow();
    }

    /** 事件总线订阅者：把事件落库，失败只告警。 */
    public ExecutionEventSink sink() {
        return this::append;
    }

    /**
     * 追加一条事件。
     *
     * <p>同一 {@code (execution_id, seq)} 重复写入按幂等忽略（重试/重放场景）。
     */
    public void append(ExecutionEvent event) {
        if (event == null) {
            return;
        }
        ExecutionEventRecord record = new ExecutionEventRecord();
        record.setExecutionId(event.executionId());
        record.setSeq(event.seq());
        record.setEventType(event.type());
        try {
            record.setPayloadJson(codec.toJson(event.payload()));
        } catch (IllegalArgumentException e) {
            record.setPayloadJson("{}");
        }
        record.setCreateTime(event.occurredAt() == null ? LocalDateTime.now()
                : LocalDateTime.ofInstant(event.occurredAt(), ZoneId.systemDefault()));
        try {
            mapper.insert(record);
        } catch (DuplicateKeyException e) {
            log.debug("执行事件已存在，跳过 executionId={} seq={}", event.executionId(), event.seq());
        } catch (Exception e) {
            log.warn("执行事件落库失败（不影响执行） executionId={} seq={} error={}",
                    event.executionId(), event.seq(), e.getMessage());
        }
    }

    /**
     * 读取指定序号之后的事件。
     *
     * @param executionId 执行标识
     * @param fromSeq     只返回 seq 大于该值的事件
     * @param limit       单次上限
     * @return 事件列表；表不可用或解析失败时返回空列表
     */
    public List<ExecutionEvent> after(String executionId, long fromSeq, int limit) {
        if (executionId == null || executionId.isBlank() || limit <= 0) {
            return List.of();
        }
        try {
            List<ExecutionEventRecord> rows = mapper.selectAfter(executionId, Math.max(0L, fromSeq), limit);
            List<ExecutionEvent> events = new ArrayList<>(rows.size());
            for (ExecutionEventRecord row : rows) {
                events.add(new ExecutionEvent(row.getExecutionId(), row.getSeq(), row.getEventType(),
                        parse(row.getPayloadJson()), row.getCreateTime() == null ? null
                        : row.getCreateTime().atZone(ZoneId.systemDefault()).toInstant()));
            }
            return events;
        } catch (Exception e) {
            log.warn("执行事件读取失败 executionId={} error={}", executionId, e.getMessage());
            return List.of();
        }
    }

    /** 清理历史事件（由调度循环低频调用）。 */
    public int purgeBefore(LocalDateTime before) {
        if (before == null) {
            return 0;
        }
        try {
            return mapper.purgeBefore(before);
        } catch (Exception e) {
            log.warn("执行事件清理失败: {}", e.getMessage());
            return 0;
        }
    }

    private Map<String, Object> parse(String json) {
        try {
            return codec.fromJson(json);
        } catch (IllegalArgumentException e) {
            return Map.of();
        }
    }

    private void purgeSafely() {
        Duration retention = properties.getEventTableRetention();
        if (retention == null || retention.isZero() || retention.isNegative()) {
            return;
        }
        int purged = purgeBefore(LocalDateTime.now().minus(retention));
        if (purged > 0) {
            log.info("执行事件表清理完成 purged={} retention={}", purged, retention);
        }
    }
}
