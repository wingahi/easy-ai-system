package com.wgh.rag.common.log;

import org.slf4j.MDC;

import java.util.Map;
import java.util.concurrent.Callable;

/**
 * MDC（日志上下文）跨线程传递工具。
 * <p>
 * Web 请求线程的 MDC 不会自动传递到自建线程/线程池，SSE 与异步链路需显式
 * {@link #copy()} + {@link #wrap(Runnable)}，任务结束时恢复原上下文，避免
 * 线程复用造成 requestId 串号。
 */
public final class MdcContext {

    private MdcContext() {
    }

    /** 拷贝当前线程的 MDC 快照，供子线程恢复。 */
    public static Map<String, String> copy() {
        return MDC.getCopyOfContextMap();
    }

    /** 包装 Runnable：执行前恢复提交时的 MDC，执行后还原。 */
    public static Runnable wrap(Runnable task) {
        Map<String, String> snapshot = copy();
        return () -> {
            Map<String, String> previous = copy();
            apply(snapshot);
            try {
                task.run();
            } finally {
                apply(previous);
            }
        };
    }

    /** 包装 Callable：语义同 {@link #wrap(Runnable)}，并保留返回值与异常。 */
    public static <V> Callable<V> wrap(Callable<V> task) {
        Map<String, String> snapshot = copy();
        return () -> {
            Map<String, String> previous = copy();
            apply(snapshot);
            try {
                return task.call();
            } finally {
                apply(previous);
            }
        };
    }

    private static void apply(Map<String, String> context) {
        if (context == null || context.isEmpty()) {
            MDC.clear();
        } else {
            MDC.setContextMap(context);
        }
    }
}
