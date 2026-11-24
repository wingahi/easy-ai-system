package com.wgh.rag.chat.service.stream.replay;

import com.wgh.rag.common.security.TenantContext;
import com.wgh.rag.config.redis.RedisCommandPort;
import com.wgh.rag.config.redis.RedisKeyNamespace;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 跨副本可见的 SSE 事件重放缓冲（Redis）。
 *
 * <p>数据结构（均带 TTL，无后台清扫任务）：</p>
 * <ul>
 *   <li>{@code {root}:stream:buf:{streamId}} —— List，元素为
 *       {@code {"seq":n,"type":"...","data":...}} 的 JSON 文本，按写入顺序排列；</li>
 *   <li>{@code {root}:stream:meta:{streamId}} —— 流元信息
 *       {@code kind:subjectId:status:lastSeq}，供重连方判断归属与终态；</li>
 *   <li>{@code {root}:stream:cur:{kind}:{subjectId}} —— 当前流指针，
 *       同一会话/运行的新生成会覆盖它，旧流因此不可再被重连接管。</li>
 * </ul>
 *
 * <p>本类只做存取，不吞异常：Redis 故障由 {@link StreamReplayService} 与
 * {@link ReplayableStreamSink} 降级并上报指标/日志。</p>
 */
@Slf4j
@Component
public class StreamReplayBuffer {

    /** 流状态：生成中 */
    public static final String STATUS_RUNNING = "running";
    /** 流状态：正常结束 */
    public static final String STATUS_COMPLETED = "completed";
    /** 流状态：异常结束 */
    public static final String STATUS_FAILED = "failed";
    /** 流状态：缓冲写入降级（重放不完整） */
    public static final String STATUS_DEGRADED = "degraded";

    private final RedisCommandPort redis;
    private final StreamReplayProperties properties;

    public StreamReplayBuffer(RedisCommandPort redis, StreamReplayProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    /** Redis 是否可用于重放（配置启用且连接可用） */
    public boolean available() {
        return redis.enabled();
    }

    /** 建立流元信息并占用所属对象（kind + subject）的「当前流」指针。 */
    public void initialize(String streamId, String kind, Long subjectId) {
        Duration ttl = properties.getTtl();
        redis.set(metaKey(streamId), encodeMeta(kind, subjectId, STATUS_RUNNING, 0L), ttl);
        redis.set(currentKey(kind, subjectId), streamId, ttl);
    }

    /** 批量追加事件；由 Lua 原子完成「追加 + 上限截断 + 续期」。 */
    public void append(String streamId, List<String> entries) {
        redis.appendBoundedList(bufKey(streamId), entries,
                properties.getMaxEventsPerStream(), properties.getTtl());
    }

    /** 读取整条流（用于重连后的首轮回放）。 */
    public List<String> readAll(String streamId) {
        return redis.rangeList(bufKey(streamId), 0, -1);
    }

    /** 读取尾部若干条（用于持续跟随）。 */
    public List<String> readTail(String streamId, int count) {
        int size = Math.max(count, 1);
        return redis.rangeList(bufKey(streamId), -size, -1);
    }

    /** 读取元信息；不存在或格式非法时返回 null。 */
    public StreamMeta readMeta(String streamId) {
        String raw = redis.get(metaKey(streamId));
        return decodeMeta(raw);
    }

    /** 标记终态，并把缓冲与当前流指针的 TTL 切换为「完成后 TTL」。 */
    public void markTerminal(String streamId, String kind, Long subjectId, String status, long lastSeq) {
        Duration ttl = properties.getCompletedTtl();
        redis.set(metaKey(streamId), encodeMeta(kind, subjectId, status, lastSeq), ttl);
        redis.expire(bufKey(streamId), ttl);
        if (kind != null && subjectId != null) {
            redis.expire(currentKey(kind, subjectId), ttl);
        }
    }

    /** 标记缓冲写入降级（流仍在生成，但重放可能缺尾部），保留活跃 TTL。 */
    public void markDegraded(String streamId, String kind, Long subjectId, long lastSeq) {
        redis.set(metaKey(streamId), encodeMeta(kind, subjectId, STATUS_DEGRADED, lastSeq),
                properties.getTtl());
    }

    /** 当前流指针是否仍指向该流；指针已过期时不拦截（流本身仍可重放）。 */
    public boolean isCurrent(String kind, Long subjectId, String streamId) {
        String current = redis.get(currentKey(kind, subjectId));
        return current == null || current.equals(streamId);
    }

    // ==================== key 与编解码 ====================
    // 租户命名空间：同一 streamId 在不同租户下互不可见，避免跨租户重放缓存泄露

    private String bufKey(String streamId) {
        return tenantRoot() + ":stream:buf:" + streamId;
    }

    private String metaKey(String streamId) {
        return tenantRoot() + ":stream:meta:" + streamId;
    }

    private String currentKey(String kind, Long subjectId) {
        return tenantRoot() + ":stream:cur:" + kind + ":" + subjectId;
    }

    private static String tenantRoot() {
        return RedisKeyNamespace.root() + ":t:" + TenantContext.tenantIdOrDefault();
    }

    private static String encodeMeta(String kind, Long subjectId, String status, long lastSeq) {
        return kind + ":" + (subjectId == null ? "" : subjectId) + ":" + status + ":" + lastSeq;
    }

    private static StreamMeta decodeMeta(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] parts = raw.split(":");
        if (parts.length < 4) {
            log.debug("忽略格式非法的流元信息: {}", raw);
            return null;
        }
        Long subjectId = null;
        try {
            subjectId = parts[1].isBlank() ? null : Long.valueOf(parts[1]);
        } catch (NumberFormatException e) {
            return null;
        }
        long lastSeq = 0L;
        try {
            lastSeq = Long.parseLong(parts[3]);
        } catch (NumberFormatException e) {
            return null;
        }
        return new StreamMeta(parts[0], subjectId, parts[2], lastSeq);
    }

    /**
     * 流元信息。
     *
     * @param kind      流类别（chat / workflow-hitl）
     * @param subjectId 所属对象（会话 id / 运行 id）
     * @param status    running / completed / failed / degraded
     * @param lastSeq   已写入缓冲的最后一个事件序号
     */
    public record StreamMeta(String kind, Long subjectId, String status, long lastSeq) {

        public boolean terminal() {
            return STATUS_COMPLETED.equals(status) || STATUS_FAILED.equals(status);
        }

        public boolean degraded() {
            return STATUS_DEGRADED.equals(status);
        }
    }
}
