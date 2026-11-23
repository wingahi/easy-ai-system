package com.wgh.rag.common.config;

import com.wgh.rag.common.log.MdcContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 异步线程池上下文透传：任务在提交线程之外执行时仍能读取 traceId。
 */
@DisplayName("MDC 异步上下文")
class MdcAsyncConfigTest {

    @Test
    @DisplayName("MDC-07 装饰后的任务在其它线程保留提交时的 traceId")
    void decoratedTaskCarriesTraceId() throws Exception {
        TaskDecorator decorator = new MdcAsyncConfig().mdcTaskDecorator();

        MDC.put(MdcContext.TRACE_ID_KEY, "trace-async");
        Runnable decorated = decorator.decorate(() -> assertThat(MdcContext.currentTraceId())
                .isEqualTo("trace-async"));
        MDC.clear();

        Thread worker = new Thread(decorated, "mdc-async-test");
        worker.start();
        worker.join();

        assertThat(MdcContext.currentTraceId()).isNull();
    }
}
