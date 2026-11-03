package com.wgh.rag.common.log;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MDC 跨线程透传测试：包装任务继承提交线程上下文，结束后还原，异常路径亦不残留。
 */
class MdcContextTest {

    @Test
    @DisplayName("MDC-01 Runnable 继承 requestId，执行后调用线程上下文还原")
    void wrapRunnablePropagates() {
        MDC.put("requestId", "req-parent-01");
        Runnable task = MdcContext.wrap(() -> assertEquals("req-parent-01", MDC.get("requestId")));

        MDC.remove("requestId");
        task.run();
        assertNull(MDC.get("requestId"), "包装任务结束后调用线程不应残留 requestId");
    }

    @Test
    @DisplayName("MDC-02 Runnable 抛异常时仍清理上下文")
    void wrapRunnableCleansOnException() {
        MDC.put("requestId", "req-parent-02");
        Runnable task = MdcContext.wrap((Runnable) () -> {
            throw new IllegalStateException("boom");
        });
        MDC.remove("requestId");

        assertThrows(IllegalStateException.class, task::run);
        assertNull(MDC.get("requestId"));
    }

    @Test
    @DisplayName("MDC-03 Callable 透传上下文并保留返回值")
    void wrapCallablePropagates() throws Exception {
        MDC.put("requestId", "req-parent-03");
        Callable<String> task = MdcContext.wrap((Callable<String>) () -> MDC.get("requestId"));
        MDC.remove("requestId");

        assertEquals("req-parent-03", task.call());
        assertNull(MDC.get("requestId"));
    }

    @Test
    @DisplayName("MDC-04 快照包含完整上下文键值")
    void copyReturnsSnapshot() {
        MDC.put("requestId", "req-parent-04");
        assertTrue(MdcContext.copy().containsKey("requestId"));
        MDC.clear();
    }
}
