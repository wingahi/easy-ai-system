package com.wgh.rag.chat.service.stream.replay;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * SSE 事件重放缓冲配置（{@code wgh.rag.stream.replay.*}）。
 *
 * <p>默认值即生产可用值，未在 {@code application.yml} 显式声明时同样生效；
 * 配置片段记录在 {@code rag-backend/config/migrations/20261124/04_stream-replay.yml}。</p>
 *
 * <p>TTL 与上限口径：<b>活跃 TTL</b> 需覆盖单次流式生成的最长耗时（聊天 30 分钟上限，取 35 分钟）；
 * <b>完成后 TTL</b> 只保留给「刚结束就重连」的迟到请求，避免 Redis 长期占用；
 * <b>单流条数上限</b> 按 token 级事件估算（5000 条 ≈ 250KB~1MB），超出后从头截断，
 * 重连方以 {@code stream_replay_gap} 事件显式告知缺口。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "wgh.rag.stream.replay")
public class StreamReplayProperties {

    /** 是否启用事件重放；关闭后退回「实时流 + 断连即取消」的既有语义 */
    private boolean enabled = true;

    /** 活跃流缓冲 TTL：每次写入续期 */
    private Duration ttl = Duration.ofMinutes(35);

    /** 流终态后的缓冲 TTL：供刚断开就重连的客户端补齐尾部 */
    private Duration completedTtl = Duration.ofMinutes(5);

    /** 单流最多保留的事件条数，超出后丢弃最旧事件 */
    private int maxEventsPerStream = 5000;

    /** 事件写入 Redis 的批量刷盘间隔：降低每 token 一次 Redis 往返的写放大 */
    private Duration flushInterval = Duration.ofMillis(200);

    /** 重连跟随的轮询间隔 */
    private Duration pollInterval = Duration.ofMillis(300);

    /** 单次轮询读取的尾部事件条数 */
    private int pollBatchSize = 200;

    /** 生成实例存活但长时间无新事件时的判定阈值（覆盖工具调用等长间隔） */
    private Duration idleTimeout = Duration.ofSeconds(120);

    /** 单次重连跟随的最长时间，需小于 SSE 连接超时（30 分钟）以便主动收口 */
    private Duration followTimeout = Duration.ofMinutes(29);
}
