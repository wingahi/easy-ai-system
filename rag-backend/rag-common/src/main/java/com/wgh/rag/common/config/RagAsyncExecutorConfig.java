package com.wgh.rag.common.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 统一受管异步执行器。
 *
 * <p>替代各模块 {@code new Thread(...).start()} 与自建裸线程池：有界队列、可辨识线程名、
 * 显式拒绝策略、MDC 透传、异常统一落日志。以 {@link AsyncTaskExecutor} 形式注入，
 * 后续调度中心（P3）只需替换该 Bean 的实现，调用方无需改动。</p>
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(RagAsyncExecutorProperties.class)
public class RagAsyncExecutorConfig {

    @Bean(name = "ragAsyncExecutor")
    @Primary
    public AsyncTaskExecutor ragAsyncExecutor(RagAsyncExecutorProperties props,
                                              TaskDecorator mdcTaskDecorator) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(props.getCoreSize());
        executor.setMaxPoolSize(props.getMaxSize());
        executor.setQueueCapacity(props.getQueueCapacity());
        executor.setKeepAliveSeconds(props.getKeepAliveSeconds());
        executor.setThreadNamePrefix(props.getThreadNamePrefix());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(props.getAwaitTerminationSeconds());
        executor.setRejectedExecutionHandler(props.isCallerRuns()
                ? new ThreadPoolExecutor.CallerRunsPolicy()
                : new ThreadPoolExecutor.AbortPolicy());
        executor.setTaskDecorator(task -> {
            Runnable mdcTask = mdcTaskDecorator.decorate(task);
            return () -> {
                try {
                    mdcTask.run();
                } catch (Throwable t) {
                    // 异步任务没有调用方 Future 承接异常，这里作为唯一落点记录，避免静默丢失
                    log.error("异步任务执行失败", t);
                }
            };
        });
        executor.initialize();
        return executor;
    }
}
