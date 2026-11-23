package com.wgh.rag.common.config;

import com.wgh.rag.common.log.MdcContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;

/**
 * Spring 异步线程池的上下文透传：Boot 3.4 会自动把该 TaskDecorator 应用到
 * applicationTaskExecutor，@Async 与 ThreadPoolTaskExecutor 提交的任务都会沿用
 * 提交时的 requestId / traceId。
 * <p>
 * 除 MDC 外，同时使用 {@link ContextPropagatingTaskDecorator} 传播 Micrometer
 * Observation / Tracing 上下文，使异步任务中的 span 仍挂在原请求链路下；未启用
 * tracing 时该装饰器为轻量 no-op。
 */
@Configuration
public class MdcAsyncConfig {

    @Bean
    public TaskDecorator mdcTaskDecorator() {
        TaskDecorator mdc = MdcContext::wrap;
        TaskDecorator tracing = new ContextPropagatingTaskDecorator();
        return task -> mdc.decorate(tracing.decorate(task));
    }
}
