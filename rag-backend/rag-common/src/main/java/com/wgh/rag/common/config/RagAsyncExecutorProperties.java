package com.wgh.rag.common.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 统一异步执行器配置。
 *
 * <p>所有配置项均有默认值，未显式配置时行为与默认参数一致；新增键：</p>
 * <ul>
 *   <li>{@code wgh.async.executor.core-size}（默认 8）</li>
 *   <li>{@code wgh.async.executor.max-size}（默认 32）</li>
 *   <li>{@code wgh.async.executor.queue-capacity}（默认 200，必须有界）</li>
 *   <li>{@code wgh.async.executor.keep-alive-seconds}（默认 60）</li>
 *   <li>{@code wgh.async.executor.thread-name-prefix}（默认 rag-async-）</li>
 *   <li>{@code wgh.async.executor.await-termination-seconds}（默认 30）</li>
 *   <li>{@code wgh.async.executor.caller-runs}（默认 false；true 时饱和回退调用线程）</li>
 * </ul>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "wgh.async.executor")
public class RagAsyncExecutorProperties {

    private int coreSize = 8;
    private int maxSize = 32;
    private int queueCapacity = 200;
    private int keepAliveSeconds = 60;
    private String threadNamePrefix = "rag-async-";
    private int awaitTerminationSeconds = 30;
    /**
     * 饱和策略：false = AbortPolicy（显式拒绝，不静默丢弃）；
     * true = CallerRunsPolicy（回退到提交线程，可能阻塞请求线程，默认关闭）。
     */
    private boolean callerRuns = false;
}
