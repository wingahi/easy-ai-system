package com.wgh.rag.common.config;

import com.wgh.rag.common.log.MdcContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;

/**
 * Spring 异步线程池的 MDC 透传：Boot 3.4 会自动把该 TaskDecorator 应用到
 * applicationTaskExecutor，@Async 与 ThreadPoolTaskExecutor 提交的任务都会沿用
 * 提交时的 requestId。
 */
@Configuration
public class MdcAsyncConfig {

    @Bean
    public TaskDecorator mdcTaskDecorator() {
        return MdcContext::wrap;
    }
}
