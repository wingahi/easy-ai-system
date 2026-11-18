package com.wgh.rag.kb.connector;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * URL 数据源连接器配置（代码默认值 + 环境变量，不改 application.yml）。
 * <p>
 * 可用环境变量：{@code WGH_CONNECTOR_SCHEDULER_ENABLED}、{@code WGH_CONNECTOR_SCHEDULER_INTERVAL_SECONDS}、
 * {@code WGH_CONNECTOR_FETCH_TIMEOUT_MS}、{@code WGH_CONNECTOR_FETCH_USER_AGENT}、
 * {@code WGH_CONNECTOR_FETCH_MAX_CONTENT_CHARS}、{@code WGH_CONNECTOR_DEFAULT_SYNC_INTERVAL_MINUTES}。
 * <p>
 * 定时同步默认关闭：服务启动后不会无条件抓取外网，需显式设置 {@code wgh.connector.scheduler.enabled=true}。
 */
@Data
@Component
public class ConnectorProperties {

    /** 定时同步总开关（默认关闭）。 */
    @Value("${wgh.connector.scheduler.enabled:false}")
    private boolean schedulerEnabled = false;

    /** 调度轮询间隔（秒）。 */
    @Value("${wgh.connector.scheduler.interval-seconds:60}")
    private int schedulerIntervalSeconds = 60;

    /** 启动后首次轮询延迟（秒），避免与启动阶段争抢资源。 */
    @Value("${wgh.connector.scheduler.initial-delay-seconds:30}")
    private int initialDelaySeconds = 30;

    /** 单轮最多处理的连接器数量。 */
    @Value("${wgh.connector.scheduler.batch-size:20}")
    private int batchSize = 20;

    /** 抓取超时（毫秒）。 */
    @Value("${wgh.connector.fetch.timeout-ms:10000}")
    private int fetchTimeoutMs = 10_000;

    /** 抓取 User-Agent。 */
    @Value("${wgh.connector.fetch.user-agent:WGH-RAG-Connector/1.0}")
    private String userAgent = "WGH-RAG-Connector/1.0";

    /** 正文最大字符数，超出截断，避免超长页面撑爆切分与嵌入。 */
    @Value("${wgh.connector.fetch.max-content-chars:200000}")
    private int maxContentChars = 200_000;

    /** 新建连接器未指定间隔时使用的默认同步间隔（分钟）。 */
    @Value("${wgh.connector.default-sync-interval-minutes:60}")
    private int defaultSyncIntervalMinutes = 60;
}
